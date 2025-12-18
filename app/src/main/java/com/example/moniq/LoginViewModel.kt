package com.example.moniq

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LoginViewModel : ViewModel() {
    private val _loginState = MutableLiveData<String>()
    val loginState: LiveData<String> = _loginState
    private val _loginSuccess = MutableLiveData<Boolean>()
    val loginSuccess: LiveData<Boolean> = _loginSuccess
    // remember the last attempted credentials so Activity can persist them on success
    var lastHost: String? = null
    var lastUser: String? = null
    var lastPass: String? = null
    var lastLegacy: Boolean = false

    fun login(host: String, username: String, password: String, legacy: Boolean) {
        _loginState.value = "Logging in..."

        // store attempted credentials
        lastHost = host
        lastUser = username
        lastPass = password
        lastLegacy = legacy

        AuthRepository().login(host, username, password, legacy) { success, error ->
            if (success) {
                if (error != null) {
                    // Server returned an error payload but we allow the user through with a warning
                    _loginState.postValue("Login warning: ${error}")
                    _loginSuccess.postValue(true)
                } else {
                    _loginState.postValue("Login successful")
                    _loginSuccess.postValue(true)
                }
            } else {
                _loginState.postValue("Login failed: ${error ?: "unknown"}")
                _loginSuccess.postValue(false)
            }
        }
    }
}
