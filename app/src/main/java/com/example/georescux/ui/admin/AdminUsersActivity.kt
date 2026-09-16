package com.example.georescux.ui.admin

import android.os.Bundle
import android.text.Editable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Administrative user directory (spec §51).
 *
 * Lists accounts known to the backend with the fields an administrator is
 * permitted to see: email, region and verification state plus a per-user SOS
 * count. Secrets, password material and identity documents are never shown
 * here — documents stay inside the authorized verification workflow only.
 */
class AdminUsersActivity : AppCompatActivity() {

    private val database by lazy { FirebaseDatabase.getInstance() }
    private lateinit var listContainer: LinearLayout
    private lateinit var summaryText: TextView

    private data class AdminUserRow(
        val uid: String,
        val email: String,
        val regionId: String?,
        val isVerified: Boolean,
        val sosCount: Int,
    )

    private val users = mutableListOf<AdminUserRow>()
    private var filter: String = ""
    private var listener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_users)

        listContainer = findViewById(R.id.usersListContainer)
        summaryText = findViewById(R.id.textUsersSummary)

        findViewById<Button>(R.id.buttonAdminUsersBack).setOnClickListener { finish() }
        findViewById<EditText>(R.id.editUserSearch).addTextChangedListener { editable: Editable? ->
            filter = editable?.toString().orEmpty().trim().lowercase()
            renderUsers()
        }

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                users.clear()
                for (userSnap in snapshot.children) {
                    val uid = userSnap.key ?: continue
                    val email = userSnap.child("email").getValue(String::class.java)
                        ?: userSnap.child("contact").getValue(String::class.java)
                        ?: "(no email on record)"
                    val regionId = userSnap.child("regionId").getValue(String::class.java)
                    val isVerified = userSnap.child("isVerified").getValue(Boolean::class.java) ?: false
                    // Count this user's own SOS records under sos_alerts/{uid}.
                    var sosCount = 0
                    for (alertSnap in userSnap.children) {
                        if (alertSnap.key == "isVerified" || alertSnap.key == "email" ||
                            alertSnap.key == "regionId" || alertSnap.key == "contact") continue
                        sosCount++
                    }
                    users.add(AdminUserRow(uid, email, regionId, isVerified, sosCount))
                }
                renderUsers()
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        database.getReference("sos_alerts").addValueEventListener(listener!!)
    }

    private fun renderUsers() {
        listContainer.removeAllViews()
        val matched = users.filter {
            filter.isBlank() ||
                it.email.lowercase().contains(filter) ||
                it.uid.lowercase().contains(filter)
        }
        val verified = matched.count { it.isVerified }
        summaryText.text = "${matched.size} account(s) in view · $verified verified"

        if (matched.isEmpty()) {
            TextView(this).apply {
                text = "No users match."
                setTextColor(ContextCompat.getColor(this@AdminUsersActivity, R.color.text_muted))
                textSize = 13f
                setPadding(24, 24, 24, 24)
            }.also { listContainer.addView(it) }
            return
        }

        matched.sortedByDescending { it.sosCount }.forEach { user ->
            val regionName = user.regionId?.let { MapRegionCatalog.byId(it)?.displayName }
            TextView(this).apply {
                text = buildString {
                    append("👤 ${user.email}\n")
                    append("UID: ${user.uid.take(12)}… · ")
                    append("Region: ${regionName ?: "not selected"}\n")
                    append("Verification: ")
                    append(if (user.isVerified) "VERIFIED (×2 priority)" else "unverified (×1 priority)")
                    append(" · SOS records: ${user.sosCount}")
                }
                setTextColor(ContextCompat.getColor(this@AdminUsersActivity, R.color.text_primary))
                textSize = 12f
                setPadding(20, 16, 20, 16)
                background = ContextCompat.getDrawable(this@AdminUsersActivity, R.drawable.bg_card_dark)
            }.also { listContainer.addView(it) }
        }
    }

    override fun onDestroy() {
        listener?.let { database.getReference("sos_alerts").removeEventListener(it) }
        super.onDestroy()
    }
}
