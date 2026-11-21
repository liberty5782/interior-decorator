package app.roomplanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.roomplanner.api.Room
import app.roomplanner.api.RoomPlannerApi
import app.roomplanner.capture.ArCoreCapture
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live AR view that shows detected floor/wall planes and uploads metric snapshots.
 *
 * This is also the Phase 0 device check: if planes appear and a snapshot uploads, ARCore works on
 * this phone. The status line reports whether the Depth API is available too.
 */
@Composable
fun CaptureScreen(api: RoomPlannerApi, room: Room, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()

    val captureRequested = remember { AtomicBoolean(false) }
    // Plain counter, not Compose state: it changes every frame and nothing displays it.
    val frameCount = remember { intArrayOf(0) }
    var tracking by remember { mutableStateOf(false) }
    var trackingHint by remember { mutableStateOf<String?>(null) }
    var floors by remember { mutableIntStateOf(0) }
    var walls by remember { mutableIntStateOf(0) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }
    var uploading by remember { mutableIntStateOf(0) }
    var uploaded by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Move the phone slowly to find the floor and walls.") }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            // AUTOMATIC: SceneView downgrades to DISABLED on phones without depth support.
            depthMode = Config.DepthMode.AUTOMATIC,
            onSessionCreated = { session ->
                depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            },
            onTrackingFailureChanged = { reason -> trackingHint = reason?.hint() },
            onSessionUpdated = { session, frame ->
                // Plane counts don't need refreshing every frame.
                if (frameCount[0]++ % 15 == 0) {
                    tracking = frame.camera.trackingState == TrackingState.TRACKING
                    var f = 0
                    var w = 0
                    for (p in session.getAllTrackables(Plane::class.java)) {
                        if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
                        when (p.type) {
                            Plane.Type.HORIZONTAL_UPWARD_FACING -> f++
                            Plane.Type.VERTICAL -> w++
                            else -> Unit
                        }
                    }
                    floors = f
                    walls = w
                }

                if (captureRequested.compareAndSet(true, false)) {
                    // Must run here: ARCore frame data is only valid inside this callback.
                    when (val result = ArCoreCapture.grab(session, frame)) {
                        is ArCoreCapture.Result.NotReady -> message = result.reason
                        is ArCoreCapture.Result.Ok -> {
                            uploading++
                            message = "Uploading…"
                            scope.launch {
                                try {
                                    val snapshot = withContext(Dispatchers.Default) {
                                        result.capture.encode()
                                    }
                                    val created = api.uploadSnapshot(room.id, snapshot)
                                    uploaded++
                                    val m = snapshot.meta
                                    message = "Saved snapshot #${created.id}: " +
                                        "${m.intrinsics.width}×${m.intrinsics.height}, " +
                                        "${m.planes.size} planes, " +
                                        if (snapshot.depth != null) "with depth" else "no depth"
                                } catch (e: Exception) {
                                    message = "Upload failed: ${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    uploading--
                                }
                            }
                        }
                    }
                }
            },
        )

        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding()
                .padding(12.dp),
        ) {
            Text(room.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(if (tracking) "Tracking" else "Not tracking")
                    append(" · floor planes: $floors · wall planes: $walls")
                    append(
                        when (depthSupported) {
                            true -> " · depth: yes"
                            false -> " · depth: no"
                            null -> ""
                        }
                    )
                },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            trackingHint?.let {
                Text(it, color = Color(0xFFFFD54F), style = MaterialTheme.typography.bodySmall)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back", color = Color.White) }
                Button(
                    onClick = { captureRequested.set(true) },
                    enabled = tracking && uploading < 3,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uploaded > 0) "Capture ($uploaded saved)" else "Capture")
                }
            }
        }
    }
}

private fun TrackingFailureReason.hint(): String? = when (this) {
    TrackingFailureReason.NONE -> null
    TrackingFailureReason.BAD_STATE -> "AR is restarting…"
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on some lights."
    TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down."
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Not enough detail. Point at textured surfaces or furniture."
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera is in use by another app."
    else -> "Tracking limited."
}
