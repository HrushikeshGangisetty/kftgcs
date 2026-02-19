package com.example.aerogcsclone.videotracking.ui

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

/**
 * Video stream player composable for RTSP/RTP/UDP video streams.
 *
 * Uses Android's MediaPlayer for RTSP streams and provides a SurfaceView
 * for efficient hardware-decoded video rendering.
 *
 * Supports:
 * - RTSP streams (rtsp://)
 * - HTTP streams (http://)
 * - UDP streams via SurfaceView
 */
@Composable
fun VideoStreamPlayer(
    streamUri: String?,
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
    onPlayerReady: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var playerState by remember { mutableStateOf(VideoPlayerState.IDLE) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (streamUri.isNullOrBlank() || !isConnected) {
        // No stream — show placeholder
        VideoPlaceholder(
            isConnected = isConnected,
            errorMessage = errorMessage,
            modifier = modifier
        )
        return
    }

    // MediaPlayer-based video rendering for RTSP
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    DisposableEffect(streamUri) {
        playerState = VideoPlayerState.LOADING
        errorMessage = null

        onDispose {
            try {
                mediaPlayer?.apply {
                    if (isPlaying) stop()
                    release()
                }
            } catch (e: Exception) {
                Timber.e(e, "VideoStreamPlayer: Error releasing player")
            }
            mediaPlayer = null
            playerState = VideoPlayerState.IDLE
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            try {
                                val mp = android.media.MediaPlayer().apply {
                                    setDataSource(ctx, Uri.parse(streamUri))
                                    setDisplay(holder)
                                    setOnPreparedListener {
                                        playerState = VideoPlayerState.PLAYING
                                        it.start()
                                        onPlayerReady?.invoke()
                                        Timber.d("VideoStreamPlayer: Playing $streamUri")
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        val err = "MediaPlayer error: what=$what extra=$extra"
                                        Timber.e("VideoStreamPlayer: $err")
                                        errorMessage = err
                                        playerState = VideoPlayerState.ERROR
                                        onError?.invoke(err)
                                        true
                                    }
                                    setOnInfoListener { _, what, _ ->
                                        when (what) {
                                            android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                                                playerState = VideoPlayerState.BUFFERING
                                            android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                                                playerState = VideoPlayerState.PLAYING
                                        }
                                        false
                                    }
                                    prepareAsync()
                                }
                                mediaPlayer = mp
                            } catch (e: Exception) {
                                Timber.e(e, "VideoStreamPlayer: Failed to create MediaPlayer")
                                errorMessage = "Failed to open stream: ${e.message}"
                                playerState = VideoPlayerState.ERROR
                                onError?.invoke(errorMessage ?: "Unknown error")
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // No action needed
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            try {
                                mediaPlayer?.apply {
                                    if (isPlaying) stop()
                                    release()
                                }
                            } catch (_: Exception) {}
                            mediaPlayer = null
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading/buffering overlay
        if (playerState == VideoPlayerState.LOADING || playerState == VideoPlayerState.BUFFERING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (playerState == VideoPlayerState.LOADING) "Connecting..." else "Buffering...",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Error overlay
        if (playerState == VideoPlayerState.ERROR) {
            VideoPlaceholder(
                isConnected = true,
                errorMessage = errorMessage,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Placeholder shown when no video stream is available or on error.
 */
@Composable
private fun VideoPlaceholder(
    isConnected: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color(0xFF0D0D1A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = if (errorMessage != null) Icons.Default.Error else Icons.Default.VideocamOff,
                contentDescription = null,
                tint = if (errorMessage != null) Color(0xFFF44336) else Color.Gray,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    errorMessage != null -> "Stream Error"
                    isConnected -> "No Video Stream"
                    else -> "Camera Offline"
                },
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    color = Color.Gray.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isConnected) "Configure RTSP/UDP stream in settings"
                    else "Connect to drone to view camera",
                    color = Color.Gray.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Video player state.
 */
private enum class VideoPlayerState {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    ERROR
}

