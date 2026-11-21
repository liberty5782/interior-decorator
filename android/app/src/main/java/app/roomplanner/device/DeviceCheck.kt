package app.roomplanner.device

import android.content.Context
import com.google.ar.core.ArCoreApk
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What this phone can do. Shown on the home screen so each device can be checked quickly. */
enum class ArSupport(val label: String, val canCapture: Boolean) {
    Ready("ARCore ready", true),
    NeedsInstall("ARCore supported. Google Play Services for AR will be installed or updated on first capture.", true),
    Unsupported("ARCore isn't available on this phone. AR capture is disabled.", false),
    Unknown("Couldn't check ARCore (offline?). You can still try capturing.", true),
}

/**
 * Asks ARCore whether this device is supported, without triggering an install. This must not
 * crash on phones without Google Play Services (e.g. de-Googled ROMs), where it reports
 * [ArSupport.Unsupported] or [ArSupport.Unknown].
 */
suspend fun checkArSupport(context: Context): ArSupport = try {
    suspendCancellableCoroutine { cont ->
        ArCoreApk.getInstance().checkAvailabilityAsync(context.applicationContext) { availability ->
            if (cont.isActive) cont.resume(availability.toSupport())
        }
    }
} catch (e: Exception) {
    ArSupport.Unknown
}

private fun ArCoreApk.Availability.toSupport(): ArSupport = when {
    this == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArSupport.Ready
    isSupported -> ArSupport.NeedsInstall
    isUnsupported -> ArSupport.Unsupported
    else -> ArSupport.Unknown
}
