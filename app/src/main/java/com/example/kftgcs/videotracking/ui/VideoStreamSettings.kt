package com.example.kftgcs.videotracking.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kftgcs.videotracking.VideoStreamInfo
import com.example.kftgcs.videotracking.VideoStreamType

/**
 * Video stream settings panel — allows the user to configure
 * the video stream source (RTSP URL, UDP port, etc.)
 *
 * Also shows any detected MAVLink video streams for quick selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoStreamSettings(
    detectedStreams: List<VideoStreamInfo>,
    onStreamSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("video_stream_prefs", Context.MODE_PRIVATE) }
    var customUrl by remember { mutableStateOf(prefs.getString("custom_stream_url", "") ?: "") }
    var selectedTab by remember { mutableStateOf(if (detectedStreams.isNotEmpty()) 0 else 1) }

    // On a phone in landscape (the usual GCS orientation) the screen is only
    // ~360dp tall, so the card must be capped and its body scrollable — otherwise
    // the "Connect to Stream" button at the bottom is clipped off-screen.
    val configuration = LocalConfiguration.current
    val maxCardHeight = (configuration.screenHeightDp.dp - 24.dp).coerceAtLeast(200.dp)
    val isCompact = configuration.screenHeightDp < 500

    Card(
        modifier = modifier
            .widthIn(max = 400.dp)
            .heightIn(max = maxCardHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (isCompact) 10.dp else 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video Stream Settings",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tabs: Detected / Custom
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Detected (${detectedStreams.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.3f),
                        selectedLabelColor = Color.White,
                        labelColor = Color.Gray
                    )
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Custom URL", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2196F3).copy(alpha = 0.3f),
                        selectedLabelColor = Color.White,
                        labelColor = Color.Gray
                    )
                )
            }

            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

            // Scrollable body: takes the leftover height so the action row below
            // stays pinned and on-screen no matter how short the display is.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    0 -> {
                        // Detected MAVLink video streams
                        if (detectedStreams.isEmpty()) {
                            Text(
                                text = "No video streams detected from drone.\nMake sure camera supports MAVLink VIDEO_STREAM_INFORMATION.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        } else {
                            detectedStreams.forEach { stream ->
                                DetectedStreamCard(
                                    stream = stream,
                                    onSelect = {
                                        onStreamSelected(stream.uri)
                                    }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }

                    1 -> {
                        // Custom URL input
                        Text(
                            text = "Stream URL",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(SIYI_MAIN_STREAM, fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF4CAF50),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                                cursorColor = Color.White,
                                focusedPlaceholderColor = Color.Gray.copy(alpha = 0.5f),
                                unfocusedPlaceholderColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick presets
                        Text(
                            text = "Quick Presets",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Presets wrap so they never push the layout wider than the card.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PresetChip("SIYI Main") { customUrl = SIYI_MAIN_STREAM }
                            PresetChip("SIYI Sub") { customUrl = SIYI_SUB_STREAM }
                            PresetChip("HereLink") { customUrl = "rtsp://192.168.43.1:8554/fpv_stream" }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "MK15 + SIYI camera: connect the tablet to the MK15 " +
                                "Wi-Fi (or USB tether), then use SIYI Main.",
                            color = Color.Gray.copy(alpha = 0.7f),
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

            // Pinned action row — never scrolls out of reach on short screens.
            if (selectedTab == 1) {
                Button(
                    onClick = {
                        if (customUrl.isNotBlank()) {
                            prefs.edit().putString("custom_stream_url", customUrl).apply()
                            onStreamSelected(customUrl.trim())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                    enabled = customUrl.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Connect to Stream", fontSize = 12.sp)
                }
            }
        }
    }
}

/** Default SIYI (A8 mini / ZT6) main H.264 stream behind an MK15 air unit. */
const val SIYI_MAIN_STREAM = "rtsp://192.168.144.25:8554/main.264"

/** SIYI secondary/lower-bitrate stream. */
const val SIYI_SUB_STREAM = "rtsp://192.168.144.25:8554/video1"

@Composable
private fun DetectedStreamCard(
    stream: VideoStreamInfo,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when (stream.type) {
                    VideoStreamType.RTSP -> Icons.Default.Videocam
                    VideoStreamType.RTPUDP -> Icons.Default.Wifi
                    else -> Icons.Default.Stream
                },
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stream.name.ifBlank { "Stream ${stream.streamId}" },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${stream.type} • ${stream.resolutionH}×${stream.resolutionV} • ${stream.framerate.toInt()}fps",
                    color = Color.Gray,
                    fontSize = 9.sp
                )
                Text(
                    text = stream.uri,
                    color = Color.Gray.copy(alpha = 0.6f),
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Default.PlayCircleOutline,
                contentDescription = "Connect",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 9.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.White.copy(alpha = 0.08f),
            labelColor = Color.White
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(28.dp)
    )
}

