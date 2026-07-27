package com.example.kftgcs.parammanagement

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import timber.log.Timber

class ParamManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val _loginState = MutableLiveData<ParamLoginState>(ParamLoginState.Idle)
    val loginState: LiveData<ParamLoginState> = _loginState

    private val _isLoggedIn = MutableLiveData(false)
    val isLoggedIn: LiveData<Boolean> = _isLoggedIn

    /** Cached user profile from the last successful real-API login (null for dummy login). */
    private val _currentUser = MutableLiveData<ParamUser?>(null)
    val currentUser: LiveData<ParamUser?> = _currentUser

    private val tokenStore = ParamAuthTokenStore(application)

    init {
        // Restore session on cold start
        if (tokenStore.getAccessToken() != null) {
            _isLoggedIn.value = true
            _currentUser.value = tokenStore.getCachedUser()
        }
    }

    companion object {
        // Dummy credentials retained for offline / demo use — DO NOT REMOVE.
        // When matched, login bypasses the network call and succeeds locally.
        private const val DUMMY_EMAIL = "kftparam.com"
        private const val DUMMY_PASSWORD = "Kft@1234"
    }

    fun login(email: String, password: String) {
        if (email.isBlank()) {
            _loginState.value = ParamLoginState.Error("Email is required")
            return
        }
        if (password.isBlank()) {
            _loginState.value = ParamLoginState.Error("Password is required")
            return
        }

        _loginState.value = ParamLoginState.Loading

        // ── 1) Dummy credentials short-circuit (preserved for offline/demo use) ──
        if (email.trim() == DUMMY_EMAIL && password == DUMMY_PASSWORD) {
            Timber.d("ParamMgmtVM: dummy credentials matched — skipping network call")
            _isLoggedIn.value = true
            _currentUser.value = null
            _loginState.value = ParamLoginState.Success
            return
        }

        // ── 2) Real authentication against http://13.233.2.207:5000/mobile/auth/login ──
        viewModelScope.launch {
            val result = ParamAuthApiService.login(
                ParamLoginRequest(email = email.trim(), password = password)
            )

            when (result) {
                is ParamAuthResult.Success -> {
                    val data = result.data
                    Timber.d("ParamMgmtVM: real login OK for ${data.user?.email ?: email}")
                    tokenStore.saveTokens(
                        accessToken = data.accessToken,
                        refreshToken = data.refreshToken,
                        expiresInSeconds = data.expiresIn,
                        user = data.user
                    )
                    _currentUser.value = data.user
                    _isLoggedIn.value = true
                    _loginState.value = ParamLoginState.Success
                }
                is ParamAuthResult.Error -> {
                    Timber.w("ParamMgmtVM: real login FAILED — ${result.message} (status=${result.statusCode})")
                    _loginState.value = ParamLoginState.Error(result.message)
                }
            }
        }
    }

    fun logout() {
        _isLoggedIn.value = false
        _currentUser.value = null
        _loginState.value = ParamLoginState.Idle
        tokenStore.clear()
    }

    fun resetState() {
        if (_loginState.value is ParamLoginState.Error) {
            _loginState.value = ParamLoginState.Idle
        }
    }
}

sealed class ParamLoginState {
    object Idle : ParamLoginState()
    object Loading : ParamLoginState()
    object Success : ParamLoginState()
    data class Error(val message: String) : ParamLoginState()
}
