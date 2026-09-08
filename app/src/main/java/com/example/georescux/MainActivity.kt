package com.example.georescux

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.georescux.ui.auth.LoginActivity
import com.example.georescux.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * Entry point of the app (declared as the launcher activity in the manifest).
 * Uses the auth repository to decide which screen to open first:
 * Home when a user is already signed in, Login otherwise.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as GeoRescuXApplication).appContainer
        if (appContainer.authRepository.isSignedIn) {
            lifecycleScope.launch {
                val role = appContainer.roleResolver.resolveRole()
                val nextScreen = if (role == com.example.georescux.data.auth.UserRole.ADMIN) {
                    com.example.georescux.ui.admin.AdminDashboardActivity::class.java
                } else {
                    HomeActivity::class.java
                }
                startActivity(Intent(this@MainActivity, nextScreen))
                finish()
            }
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
