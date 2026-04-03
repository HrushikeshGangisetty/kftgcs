package com.example.kftgcs.authentication

import android.widget.Toast
import androidx.compose.foundation.Image
import com.example.kftgcs.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.utils.AppStrings
import timber.log.Timber

@Composable
fun LoginPage(modifier: Modifier = Modifier, navController: NavController, authViewModel: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hasReadTnC by remember { mutableStateOf(false) }
    var acceptsTnC by remember { mutableStateOf(false) }
    val authState = authViewModel.authState.observeAsState()
    val context = LocalContext.current

    LaunchedEffect(authState.value) {
        Timber.d("LoginPage: Auth state changed to: ${authState.value}")
        when (val state = authState.value) {
            is AuthState.Authenticated -> {
                Timber.d("LoginPage: User authenticated, navigating to LanguageSelection")
                navController.navigate(Screen.LanguageSelection.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            }
            is AuthState.Error -> {
                Timber.e("LoginPage: Auth error: ${state.message}")
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                authViewModel.resetAuthState()
            }
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.logbag),
            contentDescription = "Login Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = AppStrings.loginWithPavaman, fontSize = 28.sp, color = Color.Black)

                Spacer(modifier = Modifier.height(4.dp))

                Text(text = AppStrings.loginCredentials, fontSize = 12.sp, color = Color.Gray)

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(text = AppStrings.email) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        cursorColor = Color.Black,
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedLabelColor = Color.Black,
                        unfocusedLabelColor = Color.Black
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = AppStrings.password) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        cursorColor = Color.Black,
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedLabelColor = Color.Black,
                        unfocusedLabelColor = Color.Black
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (authState.value is AuthState.Loading) {
                    CircularProgressIndicator(color = Color.Black)
                } else {
                    Button(
                        onClick = {
                            if (!hasReadTnC || !acceptsTnC) {
                                Toast.makeText(
                                    context,
                                    "Please read and accept the Terms & Conditions to proceed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Timber.d("LoginPage: Login button clicked - email: $email, password length: ${password.length}")
                                authViewModel.login(context, email, password)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasReadTnC && acceptsTnC
                    ) {
                        Text(text = AppStrings.login, color = if (hasReadTnC && acceptsTnC) Color.Black else Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(onClick = { navController.navigate(Screen.Signup.route) }) {
                    Text(text = "Signup", color = Color.Black)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Checkbox: I have read all T&C (with clickable T&C link)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = hasReadTnC,
                        onCheckedChange = { hasReadTnC = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF1976D2),
                            uncheckedColor = Color.Black,
                            checkmarkColor = Color.White
                        )
                    )
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = Color.Black)) {
                                append("I have read all ")
                            }
                            withStyle(
                                style = SpanStyle(
                                    color = Color(0xFF1976D2),
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append("T&C")
                            }
                        },
                        modifier = Modifier.clickable {
                            navController.navigate(Screen.TermsAndConditions.route)
                        },
                        fontSize = 14.sp
                    )
                }

                // Checkbox: I accept T&C
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = acceptsTnC,
                        onCheckedChange = { acceptsTnC = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF1976D2),
                            uncheckedColor = Color.Black,
                            checkmarkColor = Color.White
                        )
                    )
                    Text(
                        text = "I accept T&C",
                        color = Color.Black,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))


            }
        }
    }
}
