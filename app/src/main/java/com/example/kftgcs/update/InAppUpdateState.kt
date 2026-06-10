package com.example.kftgcs.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import timber.log.Timber

/**
 * What the UI should show for the Google Play in-app update flow.
 * Keeps Play Core types out of the composables that consume this.
 */
sealed interface UpdateUiState {
    /** No update, or a download already in progress — show nothing. */
    data object None : UpdateUiState

    /** A newer version is available on Play — show the "please update" popup. */
    data object Available : UpdateUiState

    /** A FLEXIBLE update finished downloading — show the "restart to install" prompt. */
    data object Downloaded : UpdateUiState
}

/**
 * Holds the in-app update state and the actions the UI can trigger.
 *
 * Created via [rememberInAppUpdateState]. We only ever use the FLEXIBLE update type
 * (background download, never blocks the app) per product decision: updates are
 * always dismissible.
 */
@Stable
class InAppUpdateState internal constructor(
    private val manager: AppUpdateManager,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>
) {
    var uiState by mutableStateOf<UpdateUiState>(UpdateUiState.None)
        private set

    /** The info backing the current [UpdateUiState.Available], needed to launch the flow. */
    private var availableInfo: AppUpdateInfo? = null

    /** Fold a fresh AppUpdateInfo snapshot into [uiState]. */
    internal fun onInfo(info: AppUpdateInfo) {
        uiState = when {
            // An update was already downloaded (e.g. before the app was backgrounded).
            info.installStatus() == InstallStatus.DOWNLOADED -> UpdateUiState.Downloaded

            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                availableInfo = info
                UpdateUiState.Available
            }

            else -> UpdateUiState.None
        }
    }

    internal fun onDownloaded() {
        uiState = UpdateUiState.Downloaded
    }

    /** Hand off to Play to start the FLEXIBLE download (Play shows its own consent UI). */
    fun startUpdate() {
        val info = availableInfo ?: return
        try {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to start in-app update flow")
        }
    }

    /** Install the already-downloaded update; this restarts the app. */
    fun completeInstall() {
        try {
            manager.completeUpdate()
        } catch (e: Exception) {
            Timber.e(e, "Failed to complete in-app update")
        }
    }

    /** Dismiss the current prompt (user chose "Later"). */
    fun dismiss() {
        uiState = UpdateUiState.None
    }
}

/**
 * Remembers an [InAppUpdateState] wired to Google Play in-app updates.
 *
 * Checks for an available (or already-downloaded) update on every ON_RESUME — i.e. on
 * first entry and whenever the app returns to the foreground, which is exactly when Play
 * recommends checking. A registered listener surfaces FLEXIBLE download completion.
 *
 * All Play calls are defensive: a Play-services failure can never crash the app, it just
 * leaves [InAppUpdateState.uiState] as [UpdateUiState.None].
 */
@Composable
fun rememberInAppUpdateState(): InAppUpdateState {
    val context = LocalContext.current
    val manager = remember { AppUpdateManagerFactory.create(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* Outcome is reflected by the listener / next resume check; nothing to do here. */ }

    val state = remember(manager, launcher) { InAppUpdateState(manager, launcher) }

    // Surface FLEXIBLE download completion.
    DisposableEffect(manager) {
        val listener = InstallStateUpdatedListener { installState ->
            if (installState.installStatus() == InstallStatus.DOWNLOADED) {
                state.onDownloaded()
            }
        }
        manager.registerListener(listener)
        onDispose { manager.unregisterListener(listener) }
    }

    // Re-check availability on each foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                manager.appUpdateInfo
                    .addOnSuccessListener { info -> state.onInfo(info) }
                    .addOnFailureListener { e -> Timber.w(e, "appUpdateInfo check failed") }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}
