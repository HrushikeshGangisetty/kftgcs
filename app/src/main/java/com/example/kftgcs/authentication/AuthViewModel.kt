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
        rePassword: String,
        signupKey: String?
    ): String? {
        // Signup-key registrations identify the pilot's company from the
        // pre-provisioned account, so no company is collected or checked.
        if (signupKey != null) {
            if (signupKey.isBlank()) return "Signup key is required"
        } else {
            // Company name validation
            if (companyName.isEmpty()) return "Please select a company name"
        }
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
        rePassword: String,
        signupKey: String? = null
    ) {
        val isDgca = signupKey != null
        if (isDgca) {
            Timber.tag(ApiService.DGCA_SIGNUP_TAG).d(
                "signup() start — DGCA flavor. Company picker skipped; company/admin resolved server-side from the pre-provisioned pilot row."
            )
        }

        val validationError = validateSignupInput(companyName, firstName, lastName, email, mobileNumber, password, rePassword, signupKey)
        if (validationError != null) {
            if (isDgca) {
                Timber.tag(ApiService.DGCA_SIGNUP_TAG).w("Client validation failed: %s", validationError)
            }
            _authState.value = AuthState.Error(validationError)
            return
        }

        _authState.value = AuthState.Loading

        viewModelScope.launch {
            try {
                // A DGCA registration carries no company_name at all: Gson
                // omits nulls, so the key never reaches the wire and the
                // backend takes its signup_key branch.
                val request = PilotRegisterRequest(
                    company_name = companyName.takeIf { it.isNotBlank() },
                    signup_key = signupKey,
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
                        // A signup-key registration comes back already verified,
                        // so there is no OTP to collect — the caller sends the
                        // user straight to login instead of the OTP screen.
                        _authState.value = if (response.data.verified == true) {
                            if (isDgca) {
                                Timber.tag(ApiService.DGCA_SIGNUP_TAG).d(
                                    "Account verified by server — skipping OTP, routing to login."
                                )
                            }
                            AuthState.RegistrationVerified(response.data.message)
                        } else {
                            if (isDgca) {
                                Timber.tag(ApiService.DGCA_SIGNUP_TAG).w(
                                    "verified != true on a signup-key registration — routing to the OTP screen."
                                )
                            }
                            AuthState.RegistrationSuccess(response.data.message)
                        }
                    }
                    is ApiResponse.Error -> {
                        if (isDgca) {
                            Timber.tag(ApiService.DGCA_SIGNUP_TAG).e(
                                "Registration rejected: %s (status %d)", response.message, response.statusCode
                            )
                        }
                        _authState.value = AuthState.Error(response.message)
                    }
                }
            } catch (e: Exception) {
                if (isDgca) {
                    Timber.tag(ApiService.DGCA_SIGNUP_TAG).e(e, "Unexpected client-side failure during registration")
                }
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun verifyOtp(context: Context, email: String, otp: String) {
        val cleanedOtp = otp.trim().filter { it.isDigit() }

        if (cleanedOtp.isEmpty()) {
            _authState.value = AuthState.Error("OTP cannot be empty")
            return
        }

        val otpInt = cleanedOtp.toIntOrNull()
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
        // Clear session and update state IMMEDIATELY (synchronously) so that any
        // composable already observing authState sees Unauthenticated before the
        // navigation happens — prevents the race condition where LoginPage briefly
        // sees Authenticated and bounces the user back to LanguageSelection.
        SessionManager.clearSession(context)
        _authState.value = AuthState.Unauthenticated

        // Fire-and-forget: tell the server about the logout in the background.
        // We do NOT wait for the response before navigating — the session is
        // already cleared locally above.
        if (email != null) {
            viewModelScope.launch {
                try {
                    ApiService.pilotLogout(PilotLogoutRequest(email))
                } catch (e: Exception) {
                    Timber.w(e, "signout: background API call failed (ignored)")
                }
            }
        }
    }

    fun resetAuthState() {
        _authState.value = AuthState.Unauthenticated
    }

    // ── Forgot Password ──────────────────────────────────────────────────

    fun sendResetOtp(email: String) {
        if (email.isBlank()) {
            _authState.value = AuthState.Error("Email is required")
            return
        }
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val request = PilotResetPasswordRequest(action = "send_otp", email = email.trim().lowercase())
                when (val response = ApiService.pilotResetPassword(request)) {
                    is ApiResponse.Success -> _authState.value = AuthState.ResetOtpSent(response.data.message ?: "OTP sent successfully")
                    is ApiResponse.Error -> _authState.value = AuthState.Error(response.message)
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun verifyResetOtp(email: String, otp: String) {
        if (otp.isBlank()) {
            _authState.value = AuthState.Error("OTP is required")
            return
        }
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val request = PilotResetPasswordRequest(action = "verify_otp", email = email.trim().lowercase(), otp = otp.trim())
                when (val response = ApiService.pilotResetPassword(request)) {
                    is ApiResponse.Success -> _authState.value = AuthState.ResetOtpVerified(response.data.message ?: "OTP verified")
                    is ApiResponse.Error -> _authState.value = AuthState.Error(response.message)
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun resetPassword(email: String, newPassword: String, confirmPassword: String) {
        if (newPassword.isBlank() || confirmPassword.isBlank()) {
            _authState.value = AuthState.Error("Both password fields are required")
            return
        }
        if (newPassword != confirmPassword) {
            _authState.value = AuthState.Error("Passwords do not match")
            return
        }
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val request = PilotResetPasswordRequest(
                    action = "reset_password",
                    email = email.trim().lowercase(),
                    new_password = newPassword,
                    confirm_password = confirmPassword
                )
                when (val response = ApiService.pilotResetPassword(request)) {
                    is ApiResponse.Success -> _authState.value = AuthState.PasswordResetSuccess(response.data.message ?: "Password reset successful")
                    is ApiResponse.Error -> _authState.value = AuthState.Error(response.message)
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Unexpected error: ${e.message}")
            }
        }
    }
}

sealed class AuthState {
    object Authenticated : AuthState()
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Error(val message: String) : AuthState()
    data class RegistrationSuccess(val message: String) : AuthState()
    /** Registration completed and already verified — no OTP step required. */
    data class RegistrationVerified(val message: String) : AuthState()
    data class OtpVerified(val message: String) : AuthState()
    data class OtpResent(val message: String) : AuthState()
    // Forgot-password flow states
    data class ResetOtpSent(val message: String) : AuthState()
    data class ResetOtpVerified(val message: String) : AuthState()
    data class PasswordResetSuccess(val message: String) : AuthState()
}
