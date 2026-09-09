package com.example.georescux.data.sync

import android.util.Log
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.RoadHazard
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Synchronizes authoritative hazards and blocked roads between Firebase Realtime Database
 * and the local [RouteRepository].
 *
 * ONLINE:
 * - Listens for cloud hazard/block updates and applies them to the local routing graph.
 * - Authorized administrators can publish and clear hazards and road blocks.
 *
 * OFFLINE:
 * - All operations fail gracefully without throwing; the local route repository remains
 *   the offline authority.
 */
class FirebaseHazardCloudDataSource(
    private val routeRepository: RouteRepository,
) {
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance() }

    private var hazardListener: ValueEventListener? = null
    private var blockedRoadListener: ValueEventListener? = null

    /**
     * Starts listening for live cloud hazard and blocked-road updates.
     * Updates are applied to the local repository so safe routes immediately reflect them.
     */
    fun startObserving() {
        if (hazardListener != null) return

        val hazardsRef = database.getReference("hazards")
        hazardListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    for (child in snapshot.children) {
                        val id = child.child("id").getValue(String::class.java) ?: child.key ?: continue
                        val fromNodeId = child.child("fromNodeId").getValue(String::class.java) ?: continue
                        val toNodeId = child.child("toNodeId").getValue(String::class.java) ?: continue
                        val penaltyMeters = child.child("penaltyMeters").getValue(Double::class.java) ?: 500.0

                        routeRepository.setHazard(
                            RoadHazard(
                                id = id,
                                fromNodeId = fromNodeId,
                                toNodeId = toNodeId,
                                penaltyMeters = penaltyMeters
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error applying cloud hazards to local graph", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Hazard listener cancelled: ${error.message}")
            }
        }.also { hazardsRef.addValueEventListener(it) }

        val blockedRef = database.getReference("blocked_roads")
        blockedRoadListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    for (child in snapshot.children) {
                        val fromNodeId = child.child("fromNodeId").getValue(String::class.java) ?: continue
                        val toNodeId = child.child("toNodeId").getValue(String::class.java) ?: continue
                        val blocked = child.child("blocked").getValue(Boolean::class.java) ?: true

                        routeRepository.setRoadBlocked(fromNodeId, toNodeId, blocked)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error applying cloud blocked roads to local graph", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Blocked road listener cancelled: ${error.message}")
            }
        }.also { blockedRef.addValueEventListener(it) }
    }

    /** Stops observing cloud updates. */
    fun stopObserving() {
        hazardListener?.let {
            database.getReference("hazards").removeEventListener(it)
            hazardListener = null
        }
        blockedRoadListener?.let {
            database.getReference("blocked_roads").removeEventListener(it)
            blockedRoadListener = null
        }
    }

    /**
     * Publishes a hazard to Firebase (Admin operation).
     */
    suspend fun publishHazard(hazard: RoadHazard): Boolean {
        return try {
            val payload = mapOf(
                "id" to hazard.id,
                "fromNodeId" to hazard.fromNodeId,
                "toNodeId" to hazard.toNodeId,
                "penaltyMeters" to hazard.penaltyMeters,
                "reportedAtMs" to System.currentTimeMillis(),
                "reportedBy" to (firebaseAuth.currentUser?.uid ?: "unknown")
            )
            database.getReference("hazards")
                .child(hazard.id)
                .setValue(payload)
                .await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish hazard ${hazard.id} to cloud", e)
            false
        }
    }

    /**
     * Removes a hazard from Firebase (Admin operation).
     */
    suspend fun clearHazard(hazardId: String): Boolean {
        return try {
            database.getReference("hazards")
                .child(hazardId)
                .removeValue()
                .await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove hazard $hazardId from cloud", e)
            false
        }
    }

    /**
     * Publishes a blocked road to Firebase (Admin operation).
     */
    suspend fun publishRoadBlocked(fromNodeId: String, toNodeId: String, blocked: Boolean): Boolean {
        val blockKey = "${fromNodeId}_${toNodeId}"
        return try {
            if (blocked) {
                val payload = mapOf(
                    "fromNodeId" to fromNodeId,
                    "toNodeId" to toNodeId,
                    "blocked" to true,
                    "updatedAtMs" to System.currentTimeMillis(),
                    "updatedBy" to (firebaseAuth.currentUser?.uid ?: "unknown")
                )
                database.getReference("blocked_roads")
                    .child(blockKey)
                    .setValue(payload)
                    .await()
            } else {
                database.getReference("blocked_roads")
                    .child(blockKey)
                    .removeValue()
                    .await()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update blocked road $blockKey in cloud", e)
            false
        }
    }

    companion object {
        private const val TAG = "FirebaseHazardSync"
    }
}
