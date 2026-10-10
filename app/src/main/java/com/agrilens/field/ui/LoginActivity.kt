package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityLoginBinding
import com.agrilens.field.model.LoginRequest
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionStore: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)

        if (sessionStore.isLoggedIn) {
            goToDashboard()
            return
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()

        if (username.isEmpty() || password.isEmpty()) {
            showError("Enter both username and password.")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.login(LoginRequest(username, password))

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    sessionStore.token = body.token
                    sessionStore.username = body.user.username
                    goToDashboard()
                } else if (response.code() == 422) {
                    showError(getString(R.string.login_error_generic))
                } else {
                    showError("Server error (${response.code()}).")
                }
            } catch (e: IOException) {
                showError(getString(R.string.login_error_network))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.login_error_generic))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }
}
