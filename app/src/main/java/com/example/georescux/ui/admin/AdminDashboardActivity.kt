package com.example.georescux.ui.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.auth.UserRole
import com.example.georescux.ui.auth.LoginActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entry point for the Admin Portal.
 * Connects directly to Firebase Realtime Database to display active emergencies,
 * user records, and live system status across all devices.
 */
class AdminDashboardActivity : AppCompatActivity() {

    private val database by lazy { FirebaseDatabase.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val appContainer = (application as GeoRescuXApplication).appContainer
        
        lifecycleScope.launch {
            val role = appContainer.roleResolver.resolveRole()
            if (role != UserRole.ADMIN) {
                finish()
                return@launch
            }
            
            setContentView(R.layout.activity_admin_dashboard)
            
            findViewById<TextView>(R.id.textViewAdminEmail).text = 
                "Logged in as Admin: ${appContainer.authRepository.currentUserEmail ?: "admin@georescux.com"}"
                
            findViewById<Button>(R.id.buttonAdminLogout).setOnClickListener {
                appContainer.authRepository.signOut()
                startActivity(Intent(this@AdminDashboardActivity, LoginActivity::class.java))
                finish()
            }

            setupRealtimeFirebaseListener()
        }
    }

    private fun setupRealtimeFirebaseListener() {
        val statusText = findViewById<TextView>(R.id.textViewAdminStatus)
        val activeCountText = findViewById<TextView>(R.id.textViewActiveEmergenciesCount)
        val totalCountText = findViewById<TextView>(R.id.textViewTotalUsersCount)
        val dataContainer = findViewById<LinearLayout>(R.id.adminDataContainer)
        val emptyText = findViewById<TextView>(R.id.textViewEmptyAdminData)

        val sosRef = database.getReference("sos_alerts")
        sosRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                statusText.text = "Firebase Status: ONLINE (Connected)"
                statusText.setTextColor(getColor(R.color.safe_green))

                dataContainer.removeAllViews()
                var activeCount = 0
                var totalCount = 0

                for (userSnap in snapshot.children) {
                    val uid = userSnap.key ?: "unknown"
                    for (alertSnap in userSnap.children) {
                        totalCount++
                        val startedAtMs = alertSnap.child("startedAtMs").getValue(Long::class.java) ?: 0L
                        val stoppedAtMs = alertSnap.child("stoppedAtMs").getValue(Long::class.java)
                        val status = alertSnap.child("status").getValue(String::class.java) ?: if (stoppedAtMs == null) "ACTIVE" else "COMPLETED"

                        val isActive = stoppedAtMs == null || status == "ACTIVE"
                        if (isActive) activeCount++

                        val locSnap = alertSnap.child("location")
                        val lat = locSnap.child("latitude").getValue(Double::class.java)
                        val lng = locSnap.child("longitude").getValue(Double::class.java)

                        val cardView = layoutInflater.inflate(R.layout.item_alert, dataContainer, false)
                        val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(startedAtMs))
                        
                        val statusEmoji = if (isActive) "🚨 ACTIVE EMERGENCY" else "✅ RESOLVED"
                        cardView.findViewById<TextView>(R.id.textAlertDate).text = "$statusEmoji · User: ${uid.take(8)}..."
                        cardView.findViewById<TextView>(R.id.textAlertDuration).text = "Time: $dateStr · Status: $status"
                        
                        val locText = if (lat != null && lng != null) {
                            String.format(Locale.US, "📍 Location: %.4f, %.4f", lat, lng)
                        } else {
                            "📍 Location: Pending fix"
                        }
                        cardView.findViewById<TextView>(R.id.textAlertLocation).text = locText

                        dataContainer.addView(cardView)
                    }
                }

                activeCountText.text = "Active Emergencies: $activeCount"
                totalCountText.text = "Total Emergency Records: $totalCount"

                if (totalCount == 0) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "No emergency records reported in Firebase yet."
                    dataContainer.addView(emptyText)
                } else {
                    emptyText.visibility = View.GONE
                }
            }

            override fun onCancelled(error: DatabaseError) {
                statusText.text = "Firebase Status: Offline / Permission Error"
                statusText.setTextColor(getColor(R.color.text_error))
                emptyText.visibility = View.VISIBLE
                emptyText.text = "Error reading Firebase database: ${error.message}"
            }
        })
    }
}
