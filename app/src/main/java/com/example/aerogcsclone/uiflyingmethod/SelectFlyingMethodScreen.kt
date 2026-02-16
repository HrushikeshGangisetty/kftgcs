package com.example.aerogcsclone.uiflyingmethod

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.aerogcsclone.R
import com.example.aerogcsclone.navigation.Screen
import com.example.aerogcsclone.telemetry.SharedViewModel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.ui.unit.Dp
import com.example.aerogcsclone.utils.AppStrings

@Composable
fun SelectFlyingMethodScreen(navController: NavController, sharedViewModel: SharedViewModel) {
    // Get the telemetry state to check if mission is paused
    val telemetryState by sharedViewModel.telemetryState.collectAsState()
    val resumePointLocation by sharedViewModel.resumePointLocation.collectAsState()

    // Clear mission data from map when user comes back to select a new flying mode
    // BUT NOT if mission is paused (user might be changing batteries and will resume)
    LaunchedEffect(Unit) {
        val missionPaused = telemetryState.missionPaused
        val hasResumePoint = resumePointLocation != null

        if (!missionPaused && !hasResumePoint) {
            sharedViewModel.clearMissionFromMap()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0E27),
                            Color(0xFF1A1F3A),
                            Color(0xFF0F1419)
                        )
                    )
                ),
            contentAlignment = Alignment.Center // center everything inside the Box
        ) {
            Column(
                modifier = Modifier
                    .wrapContentSize() // make the column wrap its content so it can be perfectly centered
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Header
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF2196F3), Color(0xFF1976D2))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.FlightTakeoff, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(AppStrings.selectFlightMode, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (AppStrings.getCurrentLanguage() == "en")
                                "Choose how you'd like to fly"
                            else
                                "మీరు ఎలా ఎగరాలనుకుంటున్నారో ఎంచుకోండి",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StyledFlyingMethodCard(
                        icon = { Image(painter = painterResource(id = R.drawable.autonomous), contentDescription = AppStrings.automatic, modifier = Modifier.size(64.dp)) },
                        label = AppStrings.automatic,
                        onClick = {
                            sharedViewModel.announceSelectedAutomatic()
                            navController.navigate(Screen.Plan.route)
                        }
                    )

                    StyledFlyingMethodCard(
                        icon = { Image(painter = painterResource(id = R.drawable.manual), contentDescription = AppStrings.manual, modifier = Modifier.size(64.dp)) },
                        label = AppStrings.manual,
                        onClick = {
                            sharedViewModel.announceSelectedManual()
                            navController.navigate(Screen.Main.route)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(AppStrings.changeLanguageLater, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StyledFlyingMethodCard(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    size: Dp = 160.dp
) {
    Card(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick)
            .shadow(10.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                icon()
                Spacer(modifier = Modifier.height(12.dp))
                Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}