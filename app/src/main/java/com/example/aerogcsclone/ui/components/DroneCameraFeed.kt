package com.example.aerogcsclone.ui.components

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.aerogcsclone.Telemetry.TelemetryState
import com.example.aerogcsclone.videotracking.*
import com.example.aerogcsclone.videotracking.ui.GimbalControlOverlay
import com.example.aerogcsclone.videotracking.ui.VideoStreamPlayer
import com.example.aerogcsclone.videotracking.ui.VideoStreamSettings
import com.example.aerogcsclone.videotracking.ui.VideoTrackingOverlay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drone Camera Feed Overlay with MissionPlanner-like Video Tracking
 *
 * Features:
 * - Live RTSP/UDP video stream via MediaPlayer SurfaceView
 * - Point tracking (tap on object) — sends MAV_CMD_CAMERA_TRACK_POINT
 * - Rectangle tracking (drag bounding box) — sends MAV_CMD_CAMERA_TRACK_RECTANGLE
 * - Visual overlay rendering (red ellipse/rectangle for tracking status)
 * - Gimbal control (D-pad for pitch/yaw, ROI setting via long-press)
 * - Geo-referencing (image point → GPS location)
 * - Camera info display (model, capabilities, tracking status)
 * - Stream settings dialog (detected MAVLink streams + custom URL)
 * - PiP mode (small) and fullscreen mode (expanded)
 */
@Composable
fun DroneCameraFeedOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    videoStreamUrl: String? = null,
    isConnected: Boolean = false,
    // Video tracking integration
    trackingState: CameraTrackingState = CameraTrackingState(),
    telemetryState: TelemetryState = TelemetryState(),
    onVideoTap: ((Float, Float) -> Unit)? = null,
    onVideoDragComplete: ((Float, Float, Float, Float) -> Unit)? = null,
    onVideoLongPress: ((Float, Float) -> Unit)? = null,
    onStopTracking: (() -> Unit)? = null,
    onGimbalNudge: ((Float, Float) -> Unit)? = null,
    onGimbalClearROI: (() -> Unit)? = null,
    onStreamSelected: ((String) -> Unit)? = null
) {
    var isExpanded by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showStreamSettings by remember { mutableStateOf(false) }
    var showGimbalControls by remember { mutableStateOf(false) }
    var activeStreamUrl by remember { mutableStateOf(videoStreamUrl) }

    // Use tracking state stream URL if available, or the passed-in URL
    val effectiveStreamUrl = activeStreamUrl
        ?: trackingState.selectedStreamUri
        ?: videoStreamUrl

    // Load saved custom URL from preferences
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (activeStreamUrl == null) {
            val prefs = context.getSharedPreferences("video_stream_prefs", Context.MODE_PRIVATE)
            val saved = prefs.getString("custom_stream_url", null)
            if (!saved.isNullOrBlank()) {
                activeStreamUrl = saved
            }
        }
    }

    // Reset offset when switching between expanded/collapsed
    LaunchedEffect(isExpanded) {
        offsetX = 0f
        offsetY = 0f
    }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    // Animated sizes
    val targetWidth = if (isExpanded) screenWidthDp else 220.dp
    val targetHeight = if (isExpanded) screenHeightDp else 160.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 300),
        label = "width"
    )
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(durationMillis = 300),
        label = "height"
    )

    val coroutineScope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier.zIndex(10f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (isExpanded) Alignment.Center else Alignment.BottomEnd
        ) {
            // Semi-transparent backdrop when expanded
            if (isExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                )
            }

            Card(
                modifier = Modifier
                    .then(
                        if (!isExpanded) {
                            Modifier
                                .padding(end = 12.dp, bottom = 12.dp)
                                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                    }
                                }
                        } else {
                            Modifier.padding(16.dp)
                        }
                    )
                    .width(animatedWidth)
                    .height(animatedHeight),
                shape = RoundedCornerShape(if (isExpanded) 16.dp else 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // ═══════════════════════════════════════════════════════
                    // VIDEO CONTENT: RTSP/MediaPlayer stream or placeholder
                    // ═══════════════════════════════════════════════════════
                    if (effectiveStreamUrl != null && isConnected) {
                        VideoStreamPlayer(
                            streamUri = effectiveStreamUrl,
                            modifier = Modifier.fillMaxSize(),
                            isConnected = isConnected
                        )
                    } else if (videoStreamUrl != null && isConnected) {
                        // Fallback to WebView for HTTP streams
                        VideoStreamView(
                            url = videoStreamUrl,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CameraPlaceholder(
                            isConnected = isConnected,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // ═══════════════════════════════════════════════════════
                    // TRACKING OVERLAY: touch interaction + visual feedback
                    // ═══════════════════════════════════════════════════════
                    if (isExpanded && isConnected && (effectiveStreamUrl != null || videoStreamUrl != null)) {
                        VideoTrackingOverlay(
                            trackingState = trackingState,
                            onTap = { x, y ->
                                onVideoTap?.invoke(x, y)
                            },
                            onDragComplete = { x1, y1, x2, y2 ->
                                onVideoDragComplete?.invoke(x1, y1, x2, y2)
                            },
                            onLongPress = { x, y ->
                                onVideoLongPress?.invoke(x, y)
                            },
                            onStopTracking = {
                                onStopTracking?.invoke()
                            },
                            modifier = Modifier.fillMaxSize(),
                            showControls = true
                        )
                    }

                    // ═══════════════════════════════════════════════════════
                    // GIMBAL CONTROLS: D-pad overlay (expanded mode only)
                    // ═══════════════════════════════════════════════════════
                    if (isExpanded && showGimbalControls) {
                        GimbalControlOverlay(
                            gimbalState = trackingState.gimbalState,
                            onNudge = { pitch, yaw ->
                                onGimbalNudge?.invoke(pitch, yaw)
                            },
                            onClearROI = {
                                onGimbalClearROI?.invoke()
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 8.dp)
                        )
                    }

                    // ═══════════════════════════════════════════════════════
                    // TOP CONTROL BAR
                    // ═══════════════════════════════════════════════════════
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(
                                    topStart = if (isExpanded) 16.dp else 12.dp,
                                    topEnd = if (isExpanded) 16.dp else 12.dp
                                )
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Camera label with live indicator + tracking status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Live indicator dot
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            trackingState.isTrackingActive -> Color(0xFF4CAF50) // Green when tracking
                                            isConnected && effectiveStreamUrl != null -> Color.Red // Red when streaming
                                            else -> Color.Gray
                                        }
                                    )
                            )
                            Text(
                                text = when {
                                    trackingState.isTrackingActive -> "TRACKING"
                                    isConnected && effectiveStreamUrl != null -> "LIVE"
                                    else -> "CAMERA"
                                },
                                color = Color.White,
                                fontSize = if (isExpanded) 14.sp else 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            // Camera model name (when detected)
                            if (trackingState.cameraDetected && isExpanded) {
                                Text(
                                    text = "• ${trackingState.cameraInfo.modelName.ifBlank { "Camera" }}",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Control buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Stream settings button
                            if (isExpanded) {
                                IconButton(
                                    onClick = { showStreamSettings = !showStreamSettings },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Stream Settings",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Gimbal toggle button
                                IconButton(
                                    onClick = { showGimbalControls = !showGimbalControls },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ControlCamera,
                                        contentDescription = "Gimbal Controls",
                                        tint = if (showGimbalControls) Color(0xFF4CAF50) else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Expand/Collapse button
                            IconButton(
                                onClick = { isExpanded = !isExpanded },
                                modifier = Modifier.size(if (isExpanded) 36.dp else 28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isExpanded) "Minimize" else "Maximize",
                                    tint = Color.White,
                                    modifier = Modifier.size(if (isExpanded) 22.dp else 16.dp)
                                )
                            }

                            // Close button
                            IconButton(
                                onClick = {
                                    isExpanded = false
                                    onDismiss()
                                },
                                modifier = Modifier.size(if (isExpanded) 36.dp else 28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(if (isExpanded) 22.dp else 16.dp)
                                )
                            }
                        }
                    }

                    // ═══════════════════════════════════════════════════════
                    // STREAM SETTINGS DIALOG (when toggled)
                    // ═══════════════════════════════════════════════════════
                    if (showStreamSettings && isExpanded) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            VideoStreamSettings(
                                detectedStreams = trackingState.videoStreams,
                                onStreamSelected = { url ->
                                    activeStreamUrl = url
                                    onStreamSelected?.invoke(url)
                                    showStreamSettings = false
                                },
                                onDismiss = { showStreamSettings = false }
                            )
                        }
                    }

                    // ═══════════════════════════════════════════════════════
                    // TRACKING HELP HINT (bottom bar in expanded mode)
                    // ═══════════════════════════════════════════════════════
                    if (isExpanded && isConnected && !trackingState.isTrackingActive) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tap: Track Point",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 9.sp
                            )
                            Text(
                                text = "•",
                                color = Color.White.copy(alpha = 0.3f),
                                fontSize = 9.sp
                            )
                            Text(
                                text = "Drag: Track Region",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 9.sp
                            )
                            Text(
                                text = "•",
                                color = Color.White.copy(alpha = 0.3f),
                                fontSize = 9.sp
                            )
                            Text(
                                text = "Long-press: Move Gimbal",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Placeholder shown when no camera stream is available
 */
@Composable
private fun CameraPlaceholder(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF0D0D1A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.VideocamOff else Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isConnected) "No Video Stream" else "Camera Offline",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isConnected)
                    "Tap ⚙ to configure stream or wait for detection"
                else
                    "Connect to drone to view camera",
                color = Color.Gray.copy(alpha = 0.6f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * WebView-based video stream viewer (fallback for HTTP streams)
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoStreamView(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        factory = {
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    domStorageEnabled = true
                }
                setBackgroundColor(android.graphics.Color.BLACK)
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) {
                webView.loadUrl(url)
            }
        },
        modifier = modifier
    )
}
