package com.example.aerogcsclone.authentication

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aerogcsclone.api.*
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    private val _authState = MutableLiveData<AuthState>()
    val authState: LiveData<AuthState> = _authState

    private val _registrationEmail = MutableLiveData<String>()
    val registrationEmail: LiveData<String> = _registrationEmail

    init {
        _authState.value = AuthState.Unauthenticated
    }

    fun checkAuthStatus(context: Context) {
        if (SessionManager.isLoggedIn(context)) {
            _authState.value = AuthState.Authenticated
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun login(context: Context, email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error("Email and password can't be empty")
            return
        }

        _authState.value = AuthState.Loading

        viewModelScope.launch {
            try {
                val request = PilotLoginRequest(email, password)
                when (val response = ApiService.pilotLogin(request)) {
                    is ApiResponse.Success -> {
                        SessionManager.saveSession(context, email, response.data.pilot_id)
                        _authState.value = AuthState.Authenticated
                    }
                    is ApiResponse.Error -> {
                        _authState.value = AuthState.Error(response.message)
                    }
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun signup(
        context: Context,
        firstName: String,
        lastName: String,
        email: String,
        mobileNumber: String,
        password: String,
        rePassword: String
    ) {
        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() ||
            mobileNumber.isEmpty() || password.isEmpty() || rePassword.isEmpty()) {
            _authState.value = AuthState.Error("All fields are required")
            return
        }

        _authState.value = AuthState.Loading

        viewModelScope.launch {
            try {
                val request = PilotRegisterRequest(
                    first_name = firstName,
                    last_name = lastName,
                    email = email,
                    mobile_no = mobileNumber,
                    password = password,
                    re_password = rePassword
                )

                when (val response = ApiService.pilotRegister(request)) {
                    is ApiResponse.Success -> {
                        _registrationEmail.value = email
                        SessionManager.saveUserDetails(context, firstName, lastName)
                        _authState.value = AuthState.RegistrationSuccess(response.data.message)
                    }
                    is ApiResponse.Error -> {
                        _authState.value = AuthState.Error(response.message)
                    }
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun verifyOtp(email: String, otp: String) {
        if (otp.isEmpty()) {
            _authState.value = AuthState.Error("OTP cannot be empty")
            return
        }

        val otpInt = otp.toIntOrNull()
        if (otpInt == null) {
            _authState.value = AuthState.Error("Invalid OTP format")
            return
        }

        _authState.value = AuthState.Loading

        viewModelScope.launch {
            try {
                val request = VerifyOtpRequest(email, otpInt)
                when (val response = ApiService.verifyOtp(request)) {
                    is ApiResponse.Success -> {
                        _authState.value = AuthState.OtpVerified(response.data.message)
                    }
                    is ApiResponse.Error -> {
                        _authState.value = AuthState.Error(response.message)
                    }
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun resendOtp(email: String) {
        _authState.value = AuthState.Loading

        viewModelScope.launch {
            try {
                val request = ResendOtpRequest(email)
                when (val response = ApiService.resendOtp(request)) {
                    is ApiResponse.Success -> {
                        _authState.value = AuthState.OtpResent(response.data.message)
                    }
                    is ApiResponse.Error -> {
                        _authState.value = AuthState.Error(response.message)
                    }
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun signout(context: Context) {
        val email = SessionManager.getEmail(context)
        if (email != null) {
            viewModelScope.launch {
                val request = PilotLogoutRequest(email)
                ApiService.pilotLogout(request)
                SessionManager.clearSession(context)
                _authState.value = AuthState.Unauthenticated
            }
        } else {
            SessionManager.clearSession(context)
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun resetAuthState() {
        _authState.value = AuthState.Unauthenticated
    }
}

sealed class AuthState {
    object Authenticated : AuthState()
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Error(val message: String) : AuthState()
    data class RegistrationSuccess(val message: String) : AuthState()
    data class OtpVerified(val message: String) : AuthState()
    data class OtpResent(val message: String) : AuthState()
}
