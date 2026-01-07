package com.example.aerogcsclone.authentication

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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.aerogcsclone.R
import com.example.aerogcsclone.navigation.Screen
import com.example.aerogcsclone.utils.AppStrings
import java.net.URLEncoder

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

    val authState by authViewModel.authState.observeAsState()
    val context = LocalContext.current

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
            is AuthState.Error -> {
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

                // First Name
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text(text = AppStrings.firstName) },
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Last Name
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text(text = AppStrings.lastName) },
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Email
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(text = AppStrings.email) },
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

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
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = AppStrings.password) },
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Re-enter Password
                OutlinedTextField(
                    value = rePassword,
                    onValueChange = { rePassword = it },
                    label = { Text(text = AppStrings.re_password) },
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
                            authViewModel.signup(
                                context,
                                firstName,
                                lastName,
                                email,
                                fullMobileNumber,
                                password,
                                rePassword
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
                label = { Text(text = "Code") },
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
            label = { Text(text = AppStrings.mobileNumber) },
            modifier = Modifier
                .width(180.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = textFieldColors,
            singleLine = true
        )
    }
}
