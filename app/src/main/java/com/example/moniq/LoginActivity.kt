package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import android.widget.Button
import android.widget.EditText
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.TextView

class LoginActivity : ComponentActivity() {
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val hostEdit = findViewById<EditText>(R.id.hostEdit)
        val userEdit = findViewById<EditText>(R.id.userEdit)
        val passEdit = findViewById<EditText>(R.id.passEdit)
        val legacySwitch = findViewById<SwitchMaterial>(R.id.legacySwitch)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        // Load any stored session and either auto-navigate to Home or pre-fill the login form
        try {
            val loaded = SessionStore.load(this)
            if (loaded) {
                // If credentials are stored, automatically proceed to the Home screen
                startActivity(android.content.Intent(this, HomeActivity::class.java))
                finish()
                return
            } else {
                // no stored session; pre-fill fields if possible
                hostEdit.setText(SessionManager.host ?: "")
                userEdit.setText(SessionManager.username ?: "")
                passEdit.setText(SessionManager.password ?: "")
                legacySwitch.isChecked = SessionManager.legacy
            }
        } catch (t: Throwable) {
            // ignore load errors and leave fields empty
        }

        viewModel.loginState.observe(this) { status ->
            statusText.text = status
        }

        viewModel.loginSuccess.observe(this) { success ->
            if (success == true) {
                // Persist the last successful credentials securely
                val h = viewModel.lastHost
                val u = viewModel.lastUser
                val p = viewModel.lastPass
                val l = viewModel.lastLegacy
                if (h != null && u != null && p != null) {
                    SessionStore.save(this, h, u, p, l)
                }

                // Open HomeActivity on successful login
                startActivity(android.content.Intent(this, HomeActivity::class.java))
                finish()
            }
        }

        loginButton.setOnClickListener {
            val host = hostEdit.text.toString().trim()
            val user = userEdit.text.toString().trim()
            val pass = passEdit.text.toString()
            val legacy = legacySwitch.isChecked

            if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                statusText.text = "Please fill in host, username and password"
                return@setOnClickListener
            }

            viewModel.login(host, user, pass, legacy)
        }
    }
}
