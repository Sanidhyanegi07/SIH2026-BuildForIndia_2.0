package com.example.georescux.data.sync

import android.util.Log
import com.example.georescux.domain.contacts.EmergencyContact
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The ONLY class in the app that touches Firebase Realtime Database.
 * Pushes the complete contact list of the authenticated user to
 * "contacts/{uid}" as one idempotent write (an empty list clears the node,
 * so deletions propagate).
 *
 * Firebase is touched lazily, only when a backup is actually attempted —
 * never during Application construction. All failures are contained and
 * reported as `false`: a backup problem can never affect local contacts.
 */
class FirebaseContactsCloudDataSource : ContactsCloudDataSource {

    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance() }

    override suspend fun uploadContacts(contacts: List<EmergencyContact>): Boolean {
        val uid = firebaseAuth.currentUser?.uid ?: return false // signed out: skip
        return try {
            val payload = ContactsCloudPayload.fromContacts(contacts)
            database.getReference("contacts")
                .child(uid)
                .setValue(payload)
                .await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, missing database, denied rules — always best-effort.
            // Still logged: a fully silent failure is undiagnosable.
            Log.w(TAG, "Contacts backup failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "FirebaseContactsSync"
    }
}
