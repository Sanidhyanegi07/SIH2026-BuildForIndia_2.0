package com.example.georescux

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.georescux.ui.auth.LoginActivity
import com.example.georescux.ui.home.HomeActivity

/**
 * Entry point of the app (declared as the launcher activity in the manifest).
 * Uses the auth repository to decide which screen to open first:
 * Home when a user is already signed in, Login otherwise.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authRepository = (application as GeoRescuXApplication).appContainer.authRepository
        val nextScreen = if (authRepository.isSignedIn) HomeActivity::class.java else LoginActivity::class.java

        startActivity(Intent(this, nextScreen))
        // Close MainActivity so that pressing Back does not return to this empty screen.
        finish()
    }
}
