package app.roomplanner.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import app.roomplanner.api.DepthMeta
import app.roomplanner.api.Intrinsics
import app.roomplanner.api.PlaneData
import app.roomplanner.api.SnapshotMeta
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream

/**
 * Everything copied out of one ARCore frame. ARCore objects are only valid during the
 * `onSessionUpdated` call that delivered the frame, so [grab] copies plain bytes and numbers, and
 * the slow JPEG encode happens later, off the render thread, in [encode].
 */
class RawCapture internal constructor(
    private val nv21: ByteArray,
    private val width: Int,
    private val height: Int,
    private val depth: ByteArray?,
    private val meta: SnapshotMeta,
) {
    /** Encode the camera image. Call off the main thread. */
    fun encode(jpegQuality: Int = 92): CapturedSnapshot {
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
        return CapturedSnapshot(jpeg = out.toByteArray(), depth = depth, meta = meta)
    }
}

/**
 * Turns the current ARCore frame into a [RawCapture].
 *
 * Conventions (see docs/api.md):
 * - The image is ARCore's CPU image in **sensor orientation** (not rotated for the display),
 *   so it matches `Camera.getPose()` and `Camera.getImageIntrinsics()` exactly.
 * - The pose is the physical camera's world-from-camera pose, OpenGL style: +X right, +Y up in
 *   the image, looking down -Z.
 * - Depth (if available) is ARCore's DEPTH16 image: millimetres, aligned with the CPU image but
 *   at lower resolution.
 */
object ArCoreCapture {

    sealed interface Result {
        data class Ok(val capture: RawCapture) : Result
        data class NotReady(val reason: String) : Result
    }

    /** Call only from `onSessionUpdated`, with the frame it just delivered. */
    fun grab(session: Session, frame: Frame): Result {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            return Result.NotReady("Not tracking yet. Move the phone slowly around the room.")
        }

        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return Result.NotReady("Camera image not ready, try again.")
        }
        val (nv21, width, height) = image.use { Triple(yuv420ToNv21(it), it.width, it.height) }

        val ki = camera.imageIntrinsics
        val focal = ki.focalLength
        val principal = ki.principalPoint
        val dims = ki.imageDimensions
        val intrinsics = Intrinsics(
            fx = focal[0], fy = focal[1],
            cx = principal[0], cy = principal[1],
            width = dims[0], height = dims[1],
        )

        val planes = session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .map { it.toPlaneData() }

        val (depth, depthMeta) = grabDepth(frame)

        val meta = SnapshotMeta(
            pose = camera.pose.toMatrixList(),
            intrinsics = intrinsics,
            planes = planes,
            depthMeta = depthMeta,
            captureSource = "arcore",
        )
        return Result.Ok(RawCapture(nv21, width, height, depth, meta))
    }

    private fun grabDepth(frame: Frame): Pair<ByteArray?, DepthMeta?> {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null to null // depth needs a moment of motion before the first frame
        } catch (e: IllegalStateException) {
            return null to null // depth disabled or unsupported on this device
        }
        return image.use {
            val plane = it.planes[0]
            val rowBytes = it.width * 2
            val src = plane.buffer.duplicate()
            val out = ByteArray(rowBytes * it.height)
            for (row in 0 until it.height) {
                src.position(row * plane.rowStride)
                src.get(out, row * rowBytes, rowBytes)
            }
            // Image buffers are native byte order, which is little-endian on Android.
            out to DepthMeta(format = "uint16_le_mm", width = it.width, height = it.height)
        }
    }

    private fun Plane.toPlaneData(): PlaneData {
        val buf = polygon.duplicate().apply { rewind() }
        val flat = FloatArray(buf.remaining()).also { buf.get(it) }
        return PlaneData(
            // ARCore returns the same Plane object for the same trackable, so this is stable
            // within one session (not across sessions).
            id = hashCode().toString(),
            type = when (type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> "horizontal_up"
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> "horizontal_down"
                Plane.Type.VERTICAL -> "vertical"
                else -> "vertical"
            },
            centerPose = centerPose.toMatrixList(),
            extentX = extentX,
            extentZ = extentZ,
            polygon = flat.toList().chunked(2),
        )
    }

    private fun Pose.toMatrixList(): List<Float> = FloatArray(16).also { toMatrix(it, 0) }.toList()

    /** YUV_420_888 to NV21 (Y plane, then interleaved V/U), honouring row and pixel strides. */
    private fun yuv420ToNv21(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) { "unexpected format ${image.format}" }
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)

        val (yPlane, uPlane, vPlane) = image.planes
        val y = yPlane.buffer.duplicate()
        var pos = 0
        for (row in 0 until h) {
            y.position(row * yPlane.rowStride)
            y.get(out, pos, w)
            pos += w
        }

        // Bulk-copy the chroma planes once, then index into arrays (much faster than get(i)).
        val u = uPlane.buffer.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        val v = vPlane.buffer.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = v[row * vRow + col * vPix]
                out[pos++] = u[row * uRow + col * uPix]
            }
        }
        return out
    }
}
