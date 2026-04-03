package com.example.kftgcs.authentication

import android.content.Context
import android.util.Patterns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kftgcs.api.*
import kotlinx.coroutines.launch
import timber.log.Timber

class AuthViewModel : ViewModel() {
    private val _authState = MutableLiveData<AuthState>()
    val authState: LiveData<AuthState> = _authState

    private val _registrationEmail = MutableLiveData<String>()
    val registrationEmail: LiveData<String> = _registrationEmail

    // Company names (admin names) for the signup dropdown
    private val _companyNames = MutableLiveData<List<String>>(emptyList())
    val companyNames: LiveData<List<String>> = _companyNames

    private val _companyNamesLoading = MutableLiveData(false)
    val companyNamesLoading: LiveData<Boolean> = _companyNamesLoading

    private val _companyNamesError = MutableLiveData<String?>(null)
    val companyNamesError: LiveData<String?> = _companyNamesError

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

    /**
     * Fetch all admin/company names from backend for the signup dropdown.
     * Safe to call multiple times — skips if already loaded.
     */
    fun fetchCompanyNames() {
        if (_companyNames.value?.isNotEmpty() == true) {
            Timber.d("Company names already loaded, skipping fetch")
            return
        }
        _companyNamesLoading.value = true
        _companyNamesError.value = null

        viewModelScope.launch {
            try {
                Timber.d("Fetching company names...")
                when (val response = ApiService.fetchAllAdmins()) {
                    is ApiResponse.Success -> {
                        val names = response.data.data.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                        _companyNames.postValue(names)
                        Timber.d("Fetched ${names.size} company names: $names")
                    }
                    is ApiResponse.Error -> {
                        _companyNamesError.postValue(response.message)
                        Timber.e("Failed to fetch company names: ${response.message} (code: ${response.statusCode})")
                    }
                }
            } catch (e: Exception) {
                _companyNamesError.postValue("Failed to load companies: ${e.message}")
                Timber.e(e, "Error fetching company names")
            } finally {
                _companyNamesLoading.postValue(false)
            }
        }
    }

    /**
     * Force retry fetching company names (clears cached list so fetchCompanyNames() won't skip).
     */
    fun retryFetchCompanyNames() {
        _companyNames.value = emptyList()
        _companyNamesError.value = null
        fetchCompanyNames()
    }

    /**
     * Validates email format and password strength for login
     * @return error message if validation fails, null if valid
     */
    private fun validateLoginInput(email: String, password: String): String? {
        if (email.isEmpty()) return "Email is required"
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return "Invalid email format"
        }
        if (email.length > 254) return "Email is too long"
        // Sanitize email - check for potentially dangerous characters
        if (email.contains(Regex("[<>\"';&|]"))) {
            return "Email contains invalid characters"
        }
        if (password.isEmpty()) return "Password is required"
        if (password.length > 128) return "Password is too long"
        return null
    }

    /**
     * Validates email format and password strength for signup
     * @return error message if validation fails, null if valid
     */
    private fun validateSignupInput(
        companyName: String,
        firstName: String,
        lastName: String,
        email: String,
        mobileNumber: String,
        password: String,
        rePassword: String
    ): String? {
        // Company name validation
        if (companyName.isEmpty()) return "Please select a company name"
        // Name validation
        if (firstName.isEmpty()) return "First name is required"
        if (firstName.length > 50) return "First name is too long (max 50 characters)"
        // Sanitize names - only allow letters, spaces, hyphens, and apostrophes
        if (!firstName.matches(Regex("^[a-zA-Z\\s'-]+$"))) {
            return "First name contains invalid characters"
        }
        if (lastName.isEmpty()) return "Last name is required"
        if (lastName.length > 50) return "Last name is too long (max 50 characters)"
        if (!lastName.matches(Regex("^[a-zA-Z\\s'-]+$"))) {
            return "Last name contains invalid characters"
        }

        // Email validation
        if (email.isEmpty()) return "Email is required"
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return "Invalid email format"
        }
        if (email.length > 254) return "Email is too long"
        // Sanitize email - check for potentially dangerous characters
        if (email.contains(Regex("[<>\"';&|]"))) {
            return "Email contains invalid characters"
        }

        // Mobile number validation
        if (mobileNumber.isEmpty()) return "Mobile number is required"
        if (!mobileNumber.matches(Regex("^[+]?[0-9]{10,15}$"))) {
            return "Invalid mobile number format"
        }

        // Password validation
        if (password.isEmpty()) return "Password is required"
        if (password.length < 8) return "Password must be at least 8 characters"
        if (password.length > 128) return "Password is too long (max 128 characters)"
        if (!password.any { it.isDigit() }) return "Password must contain at least one digit"
        if (!password.any { it.isUpperCase() }) return "Password must contain at least one uppercase letter"
        if (!password.any { it.isLowerCase() }) return "Password must contain at least one lowercase letter"
        if (!password.any { !it.isLetterOrDigit() }) return "Password must contain at least one special character"

        // Password confirmation
        if (rePassword.isEmpty()) return "Please confirm your password"
        if (password != rePassword) return "Passwords do not match"

        return null
    }

    fun login(context: Context, email: String, password: String) {
        Timber.d("=== LOGIN INITIATED ===")
        Timber.d("Login attempt for email: $email")


        val validationError = validateLoginInput(email, password)
        if (validationError != null) {
            Timber.e("Login validation failed: $validationError")
            _authState.value = AuthState.Error(validationError)
            return
        }
        Timber.d("Login validation passed")

        _authState.value = AuthState.Loading
        Timber.d("Auth state set to Loading")

        viewModelScope.launch {
            try {
                val deviceId = SessionManager.getDeviceId(context)
                val request = PilotLoginRequest(email, password, deviceId)
                Timber.d("PilotLoginRequest created - email: ${request.email}, password length: ${request.password.length}, device_id: ${request.device_id}")

                Timber.d("Calling ApiService.pilotLogin...")
                when (val response = ApiService.pilotLogin(request)) {
                    is ApiResponse.Success -> {
                        Timber.d("Login SUCCESS - pilot_id: ${response.data.pilot_id}, admin_id: ${response.data.admin_id}, superadmin_id: ${response.data.superadmin_id}, message: ${response.data.message}")
                        SessionManager.saveSession(context, email, response.data.pilot_id, response.data.admin_id, response.data.superadmin_id)
                        _authState.value = AuthState.Authenticated
                    }
                    is ApiResponse.Error -> {
                        Timber.e("Login ERROR - statusCode: ${response.statusCode}, message: ${response.message}")
                        _authState.value = AuthState.Error(response.message)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Login EXCEPTION: ${e.message}")
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }



    fun signup(
        context: Context,
        companyName: String,
        firstName: String,
        lastName: String,
        email: String,
        mobileNumber: String,
        password: String,
        rePassword: String
    ) {
        val validationError = validateSignupInput(companyName, firstName, lastName, email, mobileNumber, password, rePassword)
        if (validationError != null) {
            _authState.value = AuthState.Error(validationError)
            return
        }

        _authState.value = AuthState.Loading

        viewModelScope.launch {
            try {
                val request = PilotRegisterRequest(
                    company_name = companyName,
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

    fun verifyOtp(context: Context, email: String, otp: String) {
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
                val deviceId = SessionManager.getDeviceId(context)
                val request = VerifyOtpRequest(email, otpInt, deviceId)
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
