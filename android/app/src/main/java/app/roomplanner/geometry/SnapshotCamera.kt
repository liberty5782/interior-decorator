package app.roomplanner.geometry

/** A world point projected into the image, with its distance in front of the camera. */
data class Projected(val u: Float, val v: Float, val depth: Float)

/**
 * Pinhole camera for a saved snapshot, matching ARCore's conventions (see docs/api.md):
 * [pose] is world-from-camera; the camera looks down -Z with +X right and +Y up in the image;
 * pixel coordinates have v pointing down.
 */
class SnapshotCamera(
    val pose: Mat4,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    private val view = pose.inverseRigid()
    val position get() = pose.origin

    fun toCamera(world: Vec3): Vec3 = view.transformPoint(world)

    /** Null when the point is behind (or almost at) the camera. */
    fun project(world: Vec3): Projected? {
        val c = toCamera(world)
        val depth = -c.z
        if (depth < NEAR) return null
        return Projected(cx + fx * c.x / depth, cy - fy * c.y / depth, depth)
    }

    /** World-space ray through pixel ([u], [v]). */
    fun ray(u: Float, v: Float): Ray {
        val dirCam = Vec3((u - cx) / fx, -(v - cy) / fy, -1f)
        return Ray(position, pose.transformDir(dirCam).normalized())
    }

    /**
     * Clockwise rotation (0, 90, 180 or 270) that makes the image upright on screen.
     *
     * Snapshots are stored in sensor orientation, so a photo taken in portrait arrives sideways.
     * Instead of storing the screen rotation, this reads it from the pose: it projects world-up
     * into the image and picks the quarter turn that points it up the screen.
     */
    fun uprightRotationDegrees(): Int {
        val upCam = view.transformDir(Vec3.UP)
        // Image-space direction of "up": +x right, +y down.
        var x = upCam.x
        var y = -upCam.y
        var best = 0
        var bestScore = -y
        for (k in 1..3) {
            // Rotate 90° clockwise on a y-down screen: (x, y) -> (-y, x).
            val nx = -y
            val ny = x
            x = nx
            y = ny
            if (-y > bestScore) {
                bestScore = -y
                best = k * 90
            }
        }
        return best
    }

    companion object {
        const val NEAR = 0.05f
    }
}

/**
 * Maps image pixels to screen pixels: rotate the image upright, then scale it to fit the view and
 * centre it. [unmap] turns a touch back into image pixels for ray casting.
 */
class ImageFit(
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val viewWidth: Float,
    val viewHeight: Float,
) {
    private val quarter = (rotationDegrees / 90).mod(4)
    private val rotatedW = if (quarter % 2 == 0) imageWidth else imageHeight
    private val rotatedH = if (quarter % 2 == 0) imageHeight else imageWidth
    val scale = minOf(viewWidth / rotatedW, viewHeight / rotatedH)
    private val offsetX = (viewWidth - rotatedW * scale) / 2f
    private val offsetY = (viewHeight - rotatedH * scale) / 2f

    fun map(u: Float, v: Float): Point2 {
        val (rx, ry) = rotate(u, v)
        return Point2(offsetX + rx * scale, offsetY + ry * scale)
    }

    fun unmap(x: Float, y: Float): Point2 {
        val rx = (x - offsetX) / scale
        val ry = (y - offsetY) / scale
        return when (quarter) {
            0 -> Point2(rx, ry)
            1 -> Point2(ry, imageHeight - rx)
            2 -> Point2(imageWidth - rx, imageHeight - ry)
            else -> Point2(imageWidth - ry, rx)
        }
    }

    /** Image pixel -> pixel in the rotated (upright) image, before scaling. */
    private fun rotate(u: Float, v: Float): Pair<Float, Float> = when (quarter) {
        0 -> u to v
        1 -> (imageHeight - v) to u
        2 -> (imageWidth - u) to (imageHeight - v)
        else -> v to (imageWidth - u)
    }

    /**
     * The same mapping as a 3x3 affine matrix in android.graphics.Matrix order
     * (row-major: scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1), for drawing the bitmap.
     */
    fun affineValues(): FloatArray {
        val o = map(0f, 0f)
        val ux = map(1f, 0f)
        val vy = map(0f, 1f)
        return floatArrayOf(
            ux.x - o.x, vy.x - o.x, o.x,
            ux.y - o.y, vy.y - o.y, o.y,
            0f, 0f, 1f,
        )
    }
}
