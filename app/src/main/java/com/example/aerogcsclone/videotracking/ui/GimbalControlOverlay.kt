package com.example.aerogcsclone.videotracking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aerogcsclone.videotracking.GimbalState

/**
 * Gimbal Control Overlay — virtual D-pad for controlling gimbal pitch/yaw.
 *
 * Provides directional buttons to nudge the gimbal and a center button
 * to reset/clear ROI. Shown when video feed is expanded.
 */
@Composable
fun GimbalControlOverlay(
    gimbalState: GimbalState,
    onNudge: (Float, Float) -> Unit, // (deltaPitch, deltaYaw) in degrees
    onClearROI: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    nudgeStep: Float = 5f // degrees per button press
) {
    if (!isVisible) return

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title
        Text(
            text = "GIMBAL",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Up button (pitch up)
        GimbalButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = "Pitch Up",
            onClick = { onNudge(nudgeStep, 0f) }
        )

        // Middle row: Left, Center, Right
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left button (yaw left)
            GimbalButton(
                icon = Icons.Default.KeyboardArrowLeft,
                contentDescription = "Yaw Left",
                onClick = { onNudge(0f, -nudgeStep) }
            )

            // Center button (clear ROI / reset)
            IconButton(
                onClick = onClearROI,
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Red.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Reset Gimbal",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Right button (yaw right)
            GimbalButton(
                icon = Icons.Default.KeyboardArrowRight,
                contentDescription = "Yaw Right",
                onClick = { onNudge(0f, nudgeStep) }
            )
        }

        // Down button (pitch down)
        GimbalButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = "Pitch Down",
            onClick = { onNudge(-nudgeStep, 0f) }
        )

        // Angle readout
        if (gimbalState.isAvailable) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "P:%.0f° Y:%.0f°".format(gimbalState.pitchDeg, gimbalState.yawDeg),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun GimbalButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .background(Color.White.copy(alpha = 0.15f), CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

