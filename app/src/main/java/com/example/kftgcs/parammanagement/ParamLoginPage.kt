package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.kftgcs.navigation.Screen
import timber.log.Timber

@Composable
fun ParamLoginPage(
    modifier: Modifier = Modifier,
    navController: NavController,
    paramManagementViewModel: ParamManagementViewModel = viewModel()
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val loginState = paramManagementViewModel.loginState.observeAsState()
    val context = LocalContext.current

    LaunchedEffect(loginState.value) {
        when (val state = loginState.value) {
            is ParamLoginState.Success -> {
                Timber.d("ParamLoginPage: Login successful, navigating to Param Connection page")
                navController.navigate(Screen.ParamConnection.route) {
                    popUpTo(Screen.ParamLogin.route) { inclusive = true }
                }
            }
            is ParamLoginState.Error -> {
                Timber.e("ParamLoginPage: Login error: ${state.message}")
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                paramManagementViewModel.resetState()
            }
            else -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A237E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header icon
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "Param Management",
                tint = Color.White,
                modifier = Modifier
                    .size(64.dp)
                    .padding(bottom = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Param Management",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Login with your param credentials",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.95f))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email", color = Color.Black) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.Black),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        cursorColor = Color(0xFF1A237E),
                        focusedBorderColor = Color(0xFF1A237E),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color(0xFF1A237E),
                        unfocusedLabelColor = Color.Gray
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = Color.Black) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.Black),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        cursorColor = Color(0xFF1A237E),
                        focusedBorderColor = Color(0xFF1A237E),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color(0xFF1A237E),
                        unfocusedLabelColor = Color.Gray
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (loginState.value is ParamLoginState.Loading) {
                    CircularProgressIndicator(color = Color(0xFF1A237E))
                } else {
                    Button(
                        onClick = {
                            Timber.d("ParamLoginPage: Login clicked - email: $email")
                            paramManagementViewModel.login(email, password)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
                    ) {
                        Text(text = "Login", color = Color.White, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = { navController.popBackStack() }) {
                    Text(text = "← Back to Main Login", color = Color(0xFF1A237E))
                }
            }
        }
    }
}

