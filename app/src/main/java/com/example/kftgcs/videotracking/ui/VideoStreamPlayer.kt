package com.example.kftgcs.videotracking.ui

import android.content.Context
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
import com.example.kftgcs.videotracking.source.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Video stream player composable for the drone camera feed.
 *
 * Source-agnostic entry point: dispatches to the network (MK15/RTSP, unchanged)
 * or USB-UVC (Skydroid T12) rendering path based on [source]. Both paths share
 * the same placeholder/error/PiP chrome in `DroneCameraFeedOverlay` — only how
 * pixels get onto the surface differs.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoStreamPlayer(
    source: VideoSource?,
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
    onPlayerReady: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    when (source) {
        is VideoSource.Usb -> UsbUvcStreamPlayer(
            device = source.device,
            modifier = modifier,
            isConnected = isConnected,
            onPlayerReady = onPlayerReady,
            onError = onError
        )
        is VideoSource.Network -> VideoStreamPlayer(
            streamUri = source.uri,
            modifier = modifier,
            isConnected = isConnected,
            onPlayerReady = onPlayerReady,
            onError = onError
        )
        null -> VideoStreamPlayer(
            streamUri = null,
            modifier = modifier,
            isConnected = isConnected,
            onPlayerReady = onPlayerReady,
            onError = onError
        )
    }
}

/**
 * RTSP/HTTP network stream player — the original MK15 implementation, untouched.
 *
 * Backed by Media3/ExoPlayer. Android's built-in [android.media.MediaPlayer] cannot
 * play the RTSP streams served by SIYI cameras (A8 mini / ZT6) behind an MK15 air
 * unit — it completes the connection and then buffers indefinitely without ever
 * rendering a frame. ExoPlayer's RTSP source performs a proper DESCRIBE/SETUP/PLAY
 * handshake and depacketizes H.264 correctly. RTP is interleaved over the RTSP
 * TCP connection, matching the configuration proven against this camera.
 *
 * Note: ExoPlayer's RTSP stack does not support H.265/HEVC Aggregation Packets,
 * so the camera must be set to H.264.
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
        // Absolute minimum buffering. For a piloting aid, latency matters far more
        // than smoothness: a dropped frame is harmless, a 5-second-old picture is
        // dangerous. Start playback as soon as a single frame is decodable.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 0,
                /* maxBufferMs = */ LIVE_MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ 0,
                /* bufferForPlaybackAfterRebufferMs = */ 0
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

    // ── Live latency control ────────────────────────────────────────────────
    // RTSP over TCP never drops data, so if the decoder falls behind (a stall, a
    // packet burst, CPU contention) the backlog is played late and the feed
    // drifts permanently behind real time.
    //
    // NOTE: do NOT call seekTo() to correct this. ExoPlayer's RtspMediaPeriod
    // implements a seek that misses the local buffer as an RTSP PAUSE/PLAY
    // round-trip to the camera, which restarts the stream. Doing that on a timer
    // would stutter the picture and can drop the connection outright.
    //
    // Instead, absorb drift by playing slightly faster than real time. The
    // decoder catches up smoothly over a few seconds with no visible seam, and
    // the speed returns to 1.0 once the backlog is drained.
    LaunchedEffect(exoPlayer, streamUri) {
        var boosted = false
        while (true) {
            delay(LIVE_DRIFT_CHECK_MS)
            if (!isActuallyPlaying) continue

            val drift = exoPlayer.bufferedPosition - exoPlayer.currentPosition

            when {
                !boosted && drift > LIVE_MAX_DRIFT_MS -> {
                    Timber.d("VideoStreamPlayer: drift %d ms — catching up", drift)
                    exoPlayer.setPlaybackSpeed(LIVE_CATCHUP_SPEED)
                    boosted = true
                }

                boosted && drift < LIVE_TARGET_DRIFT_MS -> {
                    Timber.d("VideoStreamPlayer: drift %d ms — back to normal speed", drift)
                    exoPlayer.setPlaybackSpeed(1f)
                    boosted = false
                }
            }
        }
    }

    // Watchdog: if nothing has rendered after a grace period, the RTSP handshake
    // is stuck (ExoPlayer does not always surface a timeout for a half-open
    // connection). Probe the port so the user is told which stage actually failed
    // instead of watching an indefinite spinner.
    LaunchedEffect(exoPlayer, streamUri) {
        // Probe early: an unreachable camera is by far the most common cause and
        // there is no reason to make the user wait out the full RTSP timeout for
        // an answer we can get in a few seconds.
        delay(6_000)
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
                }
            },
            // The surface must be (re)attached in update, not factory: after a
            // retry the player instance is new while the TextureView is reused,
            // and a factory-only binding would leave the new player with no
            // surface — audio-less, picture-less, no error.
            update = { view ->
                // Only rebind when the player instance actually changed; update
                // runs on every recomposition and allocating a Surface each time
                // would churn graphics buffers behind a live decoder.
                val existing = view.surfaceTexture
                if (view.isAvailable && existing != null && view.tag !== exoPlayer) {
                    view.tag = exoPlayer
                    exoPlayer.setVideoSurface(android.view.Surface(existing))
                }
                view.surfaceTextureListener =
                    object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int
                        ) {
                            view.tag = exoPlayer
                            exoPlayer.setVideoSurface(android.view.Surface(surface))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture
                        ): Boolean {
                            view.tag = null
                            exoPlayer.setVideoSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture
                        ) = Unit
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
                onRetry = {
                    // Clear the failure before rebuilding the player, or this
                    // overlay would stay latched over the new one.
                    errorMessage = null
                    playerState = VideoPlayerState.LOADING
                    retryToken++
                },
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

/** Hard cap on buffered media. Anything beyond this is latency, not resilience. */
private const val LIVE_MAX_BUFFER_MS = 1_000

/** How often to check whether playback has drifted behind the live edge. */
private const val LIVE_DRIFT_CHECK_MS = 1_000L

/**
 * Maximum tolerated lag behind the buffered edge before skipping forward.
 * Kept well under the 2s operational budget so a correction happens before the
 * delay becomes noticeable to the pilot.
 */
private const val LIVE_MAX_DRIFT_MS = 700L

/** Drift we settle back to once catch-up has drained the backlog. */
private const val LIVE_TARGET_DRIFT_MS = 300L

/**
 * Catch-up rate. 1.10x drains a backlog quickly while staying visually natural;
 * higher rates read as fast-forward and make the picture hard to interpret.
 */
private const val LIVE_CATCHUP_SPEED = 1.10f

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

/**
 * Skydroid T12 video player — a USB-attached UVC (USB Video Class) camera,
 * rendered into the same [android.view.TextureView] contract as the ExoPlayer
 * path above so it can drop into the same overlay/PiP/tracking chrome.
 *
 * Unlike RTSP, there is no reconnect-by-URI here: losing the USB device means
 * losing the [android.hardware.usb.UsbDevice] handle too, so retry means asking
 * [com.example.kftgcs.videotracking.source.UsbUvcDeviceManager] to re-enumerate,
 * which is surfaced to the caller via [onError] rather than an internal retry
 * loop.
 */
@Composable
private fun UsbUvcStreamPlayer(
    device: android.hardware.usb.UsbDevice,
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
    onPlayerReady: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var playerState by remember(device) { mutableStateOf(VideoPlayerState.IDLE) }
    var errorMessage by remember(device) { mutableStateOf<String?>(null) }

    if (!isConnected) {
        VideoPlaceholder(isConnected = false, errorMessage = null, modifier = modifier)
        return
    }

    val usbManager = remember(context) {
        context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
    }
    val source = remember(device) {
        com.example.kftgcs.videotracking.source.UsbUvcVideoSource(context, usbManager, device)
    }
    var pendingSurface by remember(device) {
        mutableStateOf<android.graphics.SurfaceTexture?>(null)
    }
    var permissionGranted by remember(device) { mutableStateOf(false) }

    // USB permission is asked for once per device attach, independent of the
    // TextureView's own lifecycle (which can recreate its surface on layout
    // changes without the device actually being re-plugged).
    LaunchedEffect(device) {
        playerState = VideoPlayerState.LOADING
        errorMessage = null
        com.example.kftgcs.videotracking.source.UsbUvcDeviceManager
            .requestPermission(context, usbManager, device)
            .collect { granted ->
                if (granted) {
                    permissionGranted = true
                } else {
                    errorMessage = "USB permission for the T12 was denied."
                    playerState = VideoPlayerState.ERROR
                    onError?.invoke(errorMessage ?: "")
                }
            }
    }

    // Connect only once both the permission and the rendering surface are ready
    // — whichever arrives second triggers it.
    LaunchedEffect(permissionGranted, pendingSurface) {
        val surfaceTexture = pendingSurface ?: return@LaunchedEffect
        if (!permissionGranted) return@LaunchedEffect
        source.connect(
            surface = android.view.Surface(surfaceTexture),
            onReady = {
                playerState = VideoPlayerState.PLAYING
                errorMessage = null
                onPlayerReady?.invoke()
            },
            onError = { message ->
                Timber.e("UsbUvcStreamPlayer: %s", message)
                errorMessage = message
                playerState = VideoPlayerState.ERROR
                onError?.invoke(message)
            }
        )
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.surfaceTextureListener =
                    object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int
                        ) {
                            pendingSurface = surfaceTexture
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture
                        ): Boolean {
                            pendingSurface = null
                            source.release()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture
                        ) = Unit
                    }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (playerState == VideoPlayerState.LOADING) {
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
                    Text(text = "Connecting to T12...", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        if (playerState == VideoPlayerState.ERROR) {
            VideoPlaceholder(
                isConnected = true,
                errorMessage = errorMessage,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    DisposableEffect(device) {
        onDispose { source.release() }
    }
}
