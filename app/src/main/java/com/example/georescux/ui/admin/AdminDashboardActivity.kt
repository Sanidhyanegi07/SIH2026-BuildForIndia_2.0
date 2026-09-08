package com.example.georescux.ui.admin

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.auth.UserRole
import com.example.georescux.ui.auth.LoginActivity
import kotlinx.coroutines.launch

/**
 * Entry point for the Admin Portal.
 * Enforces secondary authorization checks and isolates admin features from the
 * normal user experience.
 */
class AdminDashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val appContainer = (application as GeoRescuXApplication).appContainer
        
        // Secondary secure check: verify Admin role dynamically before rendering
        lifecycleScope.launch {
            val role = appContainer.roleResolver.resolveRole()
            if (role != UserRole.ADMIN) {
                // Not an admin, boot them out
                finish()
                return@launch
            }
            
            // Validated
            setContentView(R.layout.activity_admin_dashboard)
            
            findViewById<TextView>(R.id.textViewAdminEmail).text = 
                "Logged in as Admin: ${appContainer.authRepository.currentUserEmail}"
                
            findViewById<Button>(R.id.buttonAdminLogout).setOnClickListener {
                appContainer.authRepository.signOut()
                startActivity(Intent(this@AdminDashboardActivity, LoginActivity::class.java))
                finish()
            }
        }
    }
}
