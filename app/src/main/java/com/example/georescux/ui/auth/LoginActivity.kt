package com.example.georescux.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.core.validation.AuthInputValidator
import com.example.georescux.ui.home.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Login screen. The UI validates the input, asks [AuthViewModel] to sign in,
 * and observes the result. Firebase is never touched here.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var authViewModel: AuthViewModel

    // Collects ViewModel state on the main thread; cancelled in onDestroy.
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val container = (application as GeoRescuXApplication).appContainer
        authViewModel = ViewModelProvider(this, AuthViewModel.Factory(container.authRepository, container.roleResolver))
            .get(AuthViewModel::class.java)

        val emailInput = findViewById<EditText>(R.id.editTextEmail)
        val passwordInput = findViewById<EditText>(R.id.editTextPassword)
        val errorText = findViewById<TextView>(R.id.textViewError)
        val loginButton = findViewById<Button>(R.id.buttonLogin)
        val registerButton = findViewById<Button>(R.id.buttonGoToRegister)

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            val validationError = AuthInputValidator.validateLogin(email, password)
            if (validationError != null) {
                showError(errorText, validationError)
                return@setOnClickListener
            }

            authViewModel.signIn(email, password)
        }

        registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        observeAuthState(errorText, loginButton)
    }

    private fun observeAuthState(errorText: TextView, loginButton: Button) {
        uiScope.launch {
            authViewModel.uiState.collect { state ->
                loginButton.isEnabled = !state.isLoading
                if (state.errorMessage != null) {
                    showError(errorText, state.errorMessage)
                } else {
                    errorText.visibility = View.GONE
                }
                if (state.isDone) {
                    if (state.role == com.example.georescux.data.auth.UserRole.ADMIN) {
                        openAdminDashboard()
                    } else {
                        openHome()
                    }
                }
            }
        }
    }

    private fun openAdminDashboard() {
        startActivity(Intent(this, com.example.georescux.ui.admin.AdminDashboardActivity::class.java))
        finish()
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        // Close Login so that pressing Back does not return to the login form.
        finish()
    }

    private fun showError(errorText: TextView, message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
