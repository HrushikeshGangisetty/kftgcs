package com.example.kftgcs.authentication

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.kftgcs.BuildConfig
import com.example.kftgcs.R
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.api.ApiService
import com.example.kftgcs.utils.AppStrings
import timber.log.Timber
import java.net.URLEncoder

// SVD pilots are pre-provisioned by their admin and receive an assigned mail ID
// plus a signup key. They register with those instead of picking a company, and
// the backend verifies them outright — so no company picker and no OTP step.
private val isSvdFlavor = BuildConfig.FLAVOR == "svd"

private val textFieldColors
    @Composable
    get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        cursorColor = Color.Black,
        focusedBorderColor = Color.Black,
        unfocusedBorderColor = Color.Black,
        focusedLabelColor = Color.Black,
        unfocusedLabelColor = Color.Black
    )

// The app-wide typography injects an explicit (white) text color that otherwise
// overrides the field `colors` above, so force black on the input text itself.
private val blackTextStyle
    @Composable
    get() = LocalTextStyle.current.copy(color = Color.Black)

@Composable
fun SignupPage(
    modifier: Modifier = Modifier,
    navController: NavController,
    authViewModel: AuthViewModel
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("+91") }
    var mobileNumber by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rePassword by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    // Assigned signup key — SVD builds only
    var signupKey by remember { mutableStateOf("") }

    // Company name dropdown state
    var selectedCompanyName by remember { mutableStateOf("") }
    var companyDropdownExpanded by remember { mutableStateOf(false) }

    val companyNames by authViewModel.companyNames.observeAsState(emptyList())
    val companyNamesLoading by authViewModel.companyNamesLoading.observeAsState(false)
    val companyNamesError by authViewModel.companyNamesError.observeAsState(null)

    val authState by authViewModel.authState.observeAsState()
    val context = LocalContext.current

    // Fetch company names when the page loads (SVD collects no company at all)
    LaunchedEffect(Unit) {
        if (!isSvdFlavor) {
            authViewModel.fetchCompanyNames()
        }
    }


    // Handle auth state changes
    LaunchedEffect(authState) {
        when (val state = authState) {
            is AuthState.RegistrationSuccess -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                if (email.isNotEmpty()) {
                    val encodedEmail = URLEncoder.encode(email, "UTF-8")
                    navController.navigate("otp_verification/$encodedEmail") {
                        popUpTo(Screen.Signup.route) { inclusive = false }
                    }
                }
            }
            is AuthState.RegistrationVerified -> {
                // Account is already verified — skip OTP and go straight to login.
                Timber.tag(ApiService.DGCA_SIGNUP_TAG).d("Navigating to Login (verified, no OTP step).")
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                authViewModel.resetAuthState()
                navController.navigate(Screen.Login.route) {
                    popUpTo(Screen.Signup.route) { inclusive = true }
                }
            }
            is AuthState.Error -> {
                if (isSvdFlavor) {
                    Timber.tag(ApiService.DGCA_SIGNUP_TAG).e("Signup error shown to user: %s", state.message)
                }
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                authViewModel.resetAuthState()
            }
            else -> { }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Background Image
        Image(
            painter = painterResource(id = R.drawable.logbag),
            contentDescription = "Signup Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Form Card
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.8f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Title
                Text(
                    text = AppStrings.signupWithPavaman,
                    fontSize = 32.sp,
                    color = Color.Black
                )
                Text(
                    text = AppStrings.createCustomCredentials,
                    fontSize = 12.sp,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Company Name Dropdown — hidden for the SVD build (fixed company)
                if (!isSvdFlavor) {
                Box {
                    OutlinedTextField(
                        value = if (companyNamesLoading) "Loading..." else selectedCompanyName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(text = AppStrings.companyName, color = Color.Black) },
                        placeholder = { Text(text = AppStrings.selectCompany, color = Color.Black) },
                        textStyle = blackTextStyle,
                        trailingIcon = {
                            if (companyNamesLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .width(20.dp)
                                        .height(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Company",
                                    modifier = Modifier.clickable {
                                        if (companyNames.isNotEmpty()) companyDropdownExpanded = true
                                    },
                                    tint = Color.Black
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            if (!companyNamesLoading && companyNames.isNotEmpty()) {
                                companyDropdownExpanded = true
                            }
                        },
                        colors = textFieldColors,
                        singleLine = true
                    )
                    DropdownMenu(
                        expanded = companyDropdownExpanded,
                        onDismissRequest = { companyDropdownExpanded = false }
                    ) {
                        companyNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name ?: "") },
                                onClick = {
                                    selectedCompanyName = name ?: ""
                                    companyDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Show error + retry if company names failed to load
                if (companyNamesError != null && !companyNamesLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = "Failed to load companies",
                            fontSize = 12.sp,
                            color = Color.Red
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { authViewModel.retryFetchCompanyNames() }) {
                            Text(text = "Retry", fontSize = 12.sp, color = Color.Blue)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                } // end company picker (non-SVD)

                // First Name
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text(text = AppStrings.firstName, color = Color.Black) },
                    textStyle = blackTextStyle,
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Last Name
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text(text = AppStrings.lastName, color = Color.Black) },
                    textStyle = blackTextStyle,
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Email
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(text = AppStrings.email, color = Color.Black) },
                    textStyle = blackTextStyle,
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Signup Key — SVD only; validated against the mail ID by the backend
                if (isSvdFlavor) {
                    OutlinedTextField(
                        value = signupKey,
                        onValueChange = { signupKey = it },
                        label = { Text(text = AppStrings.signupKey, color = Color.Black) },
                        textStyle = blackTextStyle,
                        colors = textFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Mobile Number with Country Code
                MobileNumberField(
                    countryCode = countryCode,
                    onCountryCodeChange = { countryCode = it },
                    mobileNumber = mobileNumber,
                    onMobileNumberChange = { mobileNumber = it },
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Password
                var passwordFocused by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = AppStrings.password, color = Color.Black) },
                    textStyle = blackTextStyle,
                    colors = textFieldColors,
                    modifier = Modifier.onFocusChanged { focusState ->
                        passwordFocused = focusState.isFocused
                    }
                )

                // Password requirements - only show when password field is focused
                if (passwordFocused) {
                    Column(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Password must contain:",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "• At least 8 characters",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "• At least one uppercase letter (A-Z)",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "• At least one lowercase letter (a-z)",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "• At least one number (0-9)",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "• At least one special character (!@#\$%^&*)",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Re-enter Password
                OutlinedTextField(
                    value = rePassword,
                    onValueChange = { rePassword = it },
                    label = { Text(text = AppStrings.re_password, color = Color.Black) },
                    textStyle = blackTextStyle,
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Submit Button or Loading
                if (authState is AuthState.Loading) {
                    CircularProgressIndicator(color = Color.Black)
                } else {
                    Button(
                        onClick = {
                            val fullMobileNumber = "$countryCode$mobileNumber"
                            if (isSvdFlavor) {
                                Timber.tag(ApiService.DGCA_SIGNUP_TAG).d(
                                    "Create Account tapped — email=%s mobile_no=%s signup_key_entered=%b",
                                    email.trim(), fullMobileNumber, signupKey.isNotBlank()
                                )
                            }
                            authViewModel.signup(
                                context,
                                selectedCompanyName,
                                firstName,
                                lastName,
                                // The backend matches the assigned address exactly,
                                // so strip whitespace an autofill may have added.
                                email.trim(),
                                fullMobileNumber,
                                password,
                                rePassword,
                                signupKey = signupKey.trim().takeIf { isSvdFlavor }
                            )
                        }
                    ) {
                        Text(text = AppStrings.createAccount, color = Color.Black)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Login Link
                TextButton(onClick = { navController.navigate(Screen.Login.route) }) {
                    Text(text = AppStrings.alreadyHaveAccount)
                }
            }
        }
    }
}

@Composable
private fun MobileNumberField(
    countryCode: String,
    onCountryCodeChange: (String) -> Unit,
    mobileNumber: String,
    onMobileNumberChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val countryCodes = listOf(
        "+91" to "India",
        "+1" to "USA/Canada",
        "+44" to "UK",
        "+61" to "Australia",
        "+86" to "China",
        "+81" to "Japan",
        "+49" to "Germany",
        "+33" to "France",
        "+971" to "UAE",
        "+966" to "Saudi Arabia",
        "+65" to "Singapore",
        "+60" to "Malaysia",
        "+63" to "Philippines",
        "+92" to "Pakistan",
        "+880" to "Bangladesh",
        "+94" to "Sri Lanka",
        "+977" to "Nepal"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Country Code Dropdown
        Box {
            OutlinedTextField(
                value = countryCode,
                onValueChange = { },
                readOnly = true,
                modifier = Modifier
                    .width(120.dp)
                    .clickable { onExpandedChange(true) },
                label = { Text(text = "Code", color = Color.Black) },
                textStyle = blackTextStyle,
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Country Code",
                        modifier = Modifier.clickable { onExpandedChange(true) },
                        tint = Color.Black
                    )
                },
                colors = textFieldColors,
                singleLine = true
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                countryCodes.forEach { (code, country) ->
                    DropdownMenuItem(
                        text = { Text("$code ($country)") },
                        onClick = {
                            onCountryCodeChange(code)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Mobile Number Field
        OutlinedTextField(
            value = mobileNumber,
            onValueChange = { newValue ->
                onMobileNumberChange(newValue.filter { it.isDigit() })
            },
            label = { Text(text = AppStrings.mobileNumber, color = Color.Black) },
            modifier = Modifier
                .width(180.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = blackTextStyle,
            colors = textFieldColors,
            singleLine = true
        )
    }
}
