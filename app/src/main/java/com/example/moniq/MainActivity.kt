package com.example.moniq

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Initialize session from secure storage. If a session exists, go to Home, otherwise Login.
		val hasSession = try {
			SessionStore.load(this)
		} catch (t: Throwable) {
			false
		}

		if (hasSession) {
			startActivity(Intent(this, HomeActivity::class.java))
		} else {
			startActivity(Intent(this, LoginActivity::class.java))
		}
		finish()
	}
}
