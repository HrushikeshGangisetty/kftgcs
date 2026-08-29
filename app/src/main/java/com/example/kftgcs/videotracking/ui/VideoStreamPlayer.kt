package com.example.kftgcs.videotracking.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import com.example.kftgcs.videotracking.StreamReachability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Video stream player composable for the drone camera feed.
 *
 * Backed by Media3/ExoPlayer. Android's built-in [android.media.MediaPlayer] cannot
 * play the RTSP streams served by SIYI cameras (A8 mini / ZT6) behind an MK15 air
 * unit — it completes the connection and then buffers indefinitely without ever
 * rendering a frame. ExoPlayer's RTSP source performs a proper DESCRIBE/SETUP/PLAY
 * handshake and depacketizes H.264/H.265 correctly. RTP transport is negotiated
 * as UDP first, falling back to TCP interleaving automatically on failure.
 *
 * Supports:
 * - RTSP streams (rtsp://) — e.g. rtsp://192.168.144.25:8554/main.264
 * - HTTP/HLS/progressive streams (http://, https://)
 * - UDP/RTP streams (udp://) are not playable directly; see [isPlayableUri].
 */
@OptIn(UnstableApi::class)
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

    // Bumping this re-creates the player, which is how retry-on-failure works.
    var retryToken by remember { mutableIntStateOf(0) }

    // Authoritative "video is actually flowing" signal. Playback state alone is
    // not enough: an RTSP live stream can sit in BUFFERING while frames render,
    // and an overlay keyed off state would then cover a working picture.
    var isActuallyPlaying by remember(streamUri, retryToken) { mutableStateOf(false) }

    if (streamUri.isNullOrBlank() || !isConnected) {
        VideoPlaceholder(
            isConnected = isConnected,
            errorMessage = null,
            modifier = modifier
        )
        return
    }

    if (!isPlayableUri(streamUri)) {
        VideoPlaceholder(
            isConnected = true,
            errorMessage = "Unsupported stream type. Use an rtsp:// or http:// URL.",
            modifier = modifier
        )
        return
    }

    val exoPlayer = remember(streamUri, retryToken) {
        // Small buffer + no rebuffer-after-stall keeps the feed close to live; a
        // large buffer would add seconds of latency to what is a piloting aid.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 200,
                /* maxBufferMs = */ 500,
                /* bufferForPlaybackMs = */ 100,
                /* bufferForPlaybackAfterRebufferMs = */ 100
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(buildMediaSource(context, streamUri))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        playerState = VideoPlayerState.LOADING
        errorMessage = null

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        // Keep showing "Connecting" until we have rendered once,
                        // otherwise the first connect flickers between the two labels.
                        if (playerState != VideoPlayerState.PLAYING) {
                            playerState = VideoPlayerState.LOADING
                        } else {
                            playerState = VideoPlayerState.BUFFERING
                        }
                    }

                    Player.STATE_READY -> {
                        playerState = VideoPlayerState.PLAYING
                        errorMessage = null
                        onPlayerReady?.invoke()
                        Timber.d("VideoStreamPlayer: playing %s", streamUri)
                    }

                    Player.STATE_ENDED -> playerState = VideoPlayerState.IDLE
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isActuallyPlaying = playing
                if (playing) {
                    playerState = VideoPlayerState.PLAYING
                    errorMessage = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "VideoStreamPlayer: playback error on %s", streamUri)
                val message = describeError(error, streamUri)
                errorMessage = message
                playerState = VideoPlayerState.ERROR
                onError?.invoke(message)
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            playerState = VideoPlayerState.IDLE
        }
    }

    // Watchdog: if nothing has rendered after a grace period, the RTSP handshake
    // is stuck (ExoPlayer does not always surface a timeout for a half-open
    // connection). Probe the port so the user is told which stage actually failed
    // instead of watching an indefinite spinner.
    LaunchedEffect(exoPlayer, streamUri) {
        delay(12_000)
        if (!isActuallyPlaying &&
            (playerState == VideoPlayerState.LOADING || playerState == VideoPlayerState.BUFFERING)
        ) {
            val uri = android.net.Uri.parse(streamUri)
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 554
            if (host != null) {
                val result = withContext(Dispatchers.IO) {
                    StreamReachability.probe(host, port)
                }
                if (!isActuallyPlaying && playerState != VideoPlayerState.PLAYING) {
                    errorMessage = when (result) {
                        is StreamReachability.Result.Unreachable -> result.detail
                        StreamReachability.Result.Reachable ->
                            "Reached $host:$port but no video arrived. The camera is " +
                                "on the network — check the stream path (/main.264 or " +
                                "/video1) and that the camera is powered and streaming."
                    }
                    playerState = VideoPlayerState.ERROR
                    onError?.invoke(errorMessage ?: "")
                }
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Render into a TextureView via setVideoSurface rather than a PlayerView.
        // PlayerView manages its own SurfaceView, which does not composite
        // reliably underneath the stacked overlays in this camera card — the
        // stream decodes but never becomes visible. The rover app uses this
        // TextureView path against the same camera and renders correctly.
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int
                        ) {
                            exoPlayer.setVideoSurface(android.view.Surface(surface))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture
                        ): Boolean {
                            exoPlayer.setVideoSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture
                        ) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!isActuallyPlaying &&
            (playerState == VideoPlayerState.LOADING || playerState == VideoPlayerState.BUFFERING)
        ) {
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

        if (playerState == VideoPlayerState.ERROR) {
            VideoPlaceholder(
                isConnected = true,
                errorMessage = errorMessage,
                onRetry = { retryToken++ },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Builds the right media source for the URL scheme.
 *
 * RTSP gets [RtspMediaSource] with RTP interleaved over TCP, matching the
 * configuration proven against this camera in the Pavaman rover app.
 */
@OptIn(UnstableApi::class)
private fun buildMediaSource(
    context: android.content.Context,
    streamUri: String
): MediaSource {
    val mediaItem = MediaItem.fromUri(streamUri)
    return if (streamUri.startsWith("rtsp://", ignoreCase = true)) {
        // NOTE: deliberately NOT setting a custom SocketFactory here. Binding
        // sockets to a hand-picked Network broke playback on devices where the
        // default route already reaches the camera (VLC works there precisely
        // because it does nothing special). Let the platform route it.
        //
        // NOTE: RTP-over-TCP is NOT forced. SIYI cameras serve RTP over UDP and
        // some firmware rejects or stalls on interleaved TCP, which showed up as
        // a connection failure while other apps played the same URL.
        // Matches the configuration proven to work against this exact camera
        // (SIYI on 192.168.144.25) in the Pavaman rover app: RTP interleaved
        // over TCP, no custom socket factory, no timeout override.
        RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setDebugLoggingEnabled(true)
            .createMediaSource(mediaItem)
    } else {
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(mediaItem)
    }
}

/** Schemes ExoPlayer can actually open here. Raw `udp://` RTP is not one of them. */
private fun isPlayableUri(uri: String): Boolean {
    val lower = uri.lowercase()
    return lower.startsWith("rtsp://") ||
        lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("file://") ||
        lower.startsWith("content://")
}

/**
 * Turns an ExoPlayer error into something a pilot in a field can act on.
 */
@OptIn(UnstableApi::class)
private fun describeError(error: PlaybackException, streamUri: String): String {
    val host = runCatching { android.net.Uri.parse(streamUri).host }.getOrNull() ?: "camera"
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Cannot reach $host. Check that the tablet is on the MK15 Wi-Fi / USB network."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "Camera rejected the request. Check the stream path (e.g. /main.264 or /video1)."

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Stream not found at this path. Try rtsp://$host:8554/main.264 or /video1."

        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
            "Cleartext traffic to $host is blocked by the app's network policy."

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "Cannot decode this stream. If the camera is set to H.265/HEVC, " +
                "switch it to H.264 in the SIYI camera settings."

        else -> {
            // ExoPlayer's RTSP H.265 reader throws UnsupportedOperationException
            // for Aggregation Packets (NAL type 48), which SIYI cameras emit in
            // H.265 mode. This is a known gap in the Media3 RTSP stack, not a
            // network fault, so name it rather than showing a bare error code.
            val causeChain = generateSequence(error.cause as Throwable?) { it.cause }
            if (causeChain.any { it is UnsupportedOperationException }) {
                "This camera is streaming H.265, which the player's RTSP support " +
                    "cannot decode. Set the SIYI camera to H.264 and reconnect."
            } else {
                error.errorCodeName
            }
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
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorMessage ?: if (isConnected) "Configure RTSP stream in settings"
                else "Connect to drone to view camera",
                color = Color.Gray.copy(alpha = 0.6f),
                fontSize = if (errorMessage != null) 10.sp else 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 3
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(10.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onRetry,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text("Retry", fontSize = 11.sp, color = Color.White)
                }
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
