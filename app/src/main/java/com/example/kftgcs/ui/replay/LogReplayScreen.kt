package com.example.kftgcs.ui.replay

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.kftgcs.loganalysis.model.ReplayFrame
import com.example.kftgcs.loganalysis.shareLogFile
import com.example.kftgcs.loganalysis.shareRecording
import com.example.kftgcs.ui.replay.recording.ScreenRecordController
import com.example.kftgcs.ui.replay.recording.ScreenRecordService
import java.io.File
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlinx.coroutines.delay

private val ScreenBg = Color(0xFF23272A)
private val Accent = Color(0xFF87CEEB)

/**
 * Visual flight-replay screen: a video-player-style view of a parsed flight log. Plays/pauses/scrubs the
 * decimated [ReplayFrame] timeline (handed over via [ReplaySession]) and shows an artificial horizon, a
 * synced telemetry dashboard, and a map of the path with a moving drone marker.
 *
 * Recomposition isolation (the timeline ticks 4×/sec): this screen body reads only the stable [frames]
 * list — never `controller.currentIndex`. Children receive a remembered `frameProvider` lambda and read
 * the current frame in their own scope (Canvas children read it in the draw phase), so each is an
 * independent recomposition island and none can stall the others.
 */
@Composable
fun LogReplayScreen(navController: NavHostController) {
    val frames = remember { ReplaySession.frames }
    val sourceFilePath = remember { ReplaySession.sourceFilePath }
    val context = LocalContext.current

    // Screen-record (MediaProjection): the system consent dialog returns here, then we hand the
    // result to the foreground service which records the screen to an mp4.
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val metrics = context.resources.displayMetrics
            val (w, h) = scaledRecordingSize(metrics.widthPixels, metrics.heightPixels)
            val outFile = File(context.cacheDir, "replay_${System.currentTimeMillis()}.mp4")
            ScreenRecordService.start(context, result.resultCode, data, outFile, w, h, metrics.densityDpi)
        }
    }

    // When a recording finishes, open the share sheet for it.
    LaunchedEffect(Unit) {
        snapshotFlow { ScreenRecordController.pendingShareFile }.collect { file ->
            if (file != null) {
                shareRecording(context, file)
                ScreenRecordController.pendingShareFile = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Flight Replay",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isRecording = ScreenRecordController.isRecording
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                ScreenRecordService.stop(context)
                            } else {
                                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                                if (mpm != null) captureLauncher.launch(mpm.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                            contentDescription = if (isRecording) "Stop recording" else "Record replay",
                            tint = if (isRecording) Color(0xFFE53935) else Accent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    if (sourceFilePath != null) {
                        IconButton(
                            onClick = { shareLogFile(context, File(sourceFilePath)) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Share log",
                                tint = Accent,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Accent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            HorizontalDivider(
                color = Accent,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            if (frames.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No replay data available.\nGo back and re-run the log analysis.",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
                return@Column
            }

            ReplayContent(frames)
        }
    }
}

@Composable
private fun ReplayContent(frames: List<ReplayFrame>) {
    val controller = rememberReplayController(frames.size)

    // Stable provider — defining it does not read the index; invoking it (in children) does.
    val frameProvider: () -> ReplayFrame = remember(frames, controller) {
        { controller.frameOf(frames) }
    }

    // Precompute the GPS path + bounding box once (skip frames before the first valid fix).
    val path = remember(frames) {
        frames.asSequence()
            .filter { !it.lat.isNaN() && !it.lng.isNaN() && (it.lat != 0.0 || it.lng != 0.0) }
            .map { LatLng(it.lat, it.lng) }
            .toList()
    }
    val bounds = remember(path) {
        if (path.isEmpty()) null
        else LatLngBounds.Builder().apply { path.forEach { include(it) } }.build()
    }

    // Playback ticker. Reads controller.isPlaying as the effect key (only re-runs on play/pause),
    // and mutates currentIndex — which this screen never reads, so no per-tick recomposition here.
    LaunchedEffect(controller.isPlaying) {
        while (controller.isPlaying) {
            delay(250L) // 4 Hz data → 250 ms/frame = real-time playback
            if (!controller.advance()) break
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReplayMap(
                path = path,
                bounds = bounds,
                frameProvider = frameProvider,
                modifier = Modifier
                    .weight(1.4f)
                    .fillMaxHeight()
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ArtificialHorizon(
                    frameProvider = frameProvider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                TelemetryOverlay(
                    frameProvider = frameProvider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        ReplayScrubber(
            controller = controller,
            frames = frames,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/**
 * Scale the screen resolution down so the longer side is at most [maxLong] px (keeping both sides even,
 * as required by the H.264 encoder). Tablets can exceed encoder limits at native resolution; the
 * mirrored VirtualDisplay scales the content to this size.
 */
private fun scaledRecordingSize(width: Int, height: Int, maxLong: Int = 1280): Pair<Int, Int> {
    val longSide = maxOf(width, height).coerceAtLeast(1)
    val scale = if (longSide > maxLong) maxLong.toFloat() / longSide else 1f
    fun even(v: Int): Int = (v / 2) * 2
    return even((width * scale).toInt()).coerceAtLeast(2) to even((height * scale).toInt()).coerceAtLeast(2)
}
