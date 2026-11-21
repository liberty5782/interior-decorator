package app.roomplanner.geometry

import kotlin.math.atan2

enum class PlaneKind { HorizontalUp, HorizontalDown, Vertical }

/** A detected plane: [pose] is plane-to-world with local +Y as the normal (as in ARCore). */
class PlaneGeom(
    val kind: PlaneKind,
    val pose: Mat4,
    val extentX: Float,
    val extentZ: Float,
    /** Boundary as (x, z) in the plane's local frame; null means use the extents. */
    val polygon: List<Point2>?,
) {
    private val inverse = pose.inverseRigid()
    val point get() = pose.origin
    val normal get() = pose.axisY.normalized()

    fun contains(world: Vec3): Boolean {
        val local = inverse.transformPoint(world)
        val p = Point2(local.x, local.z)
        return if (polygon != null && polygon.size >= 3) {
            pointInPolygon(p, polygon)
        } else {
            kotlin.math.abs(p.x) <= extentX / 2 && kotlin.math.abs(p.y) <= extentZ / 2
        }
    }
}

enum class ItemKind { Furniture, WallDecor }

/** Item dimensions in metres. Width is along local X, depth along Z, height along Y. */
data class ItemSize(val width: Float, val depth: Float, val height: Float)

/** Where a tap landed and the transform to give a new item there. */
data class PlacementHit(val transform: Mat4, val plane: PlaneGeom?)

sealed interface PlaceResult {
    data class Placed(val hit: PlacementHit) : PlaceResult
    data class Failed(val reason: String) : PlaceResult
}

/**
 * Placement rules shared by the snapshot planner and (later) live AR.
 *
 * Item frames (docs/api.md): furniture's origin is the centre of its footprint on the floor;
 * wall decor's origin is the centre of its back face on the wall. +Y is up and +Z is the item's
 * front (for decor: out of the wall).
 */
object PlacementRules {

    fun place(kind: ItemKind, ray: Ray, planes: List<PlaneGeom>, cameraPosition: Vec3): PlaceResult =
        when (kind) {
            ItemKind.Furniture -> placeOnFloor(ray, planes, cameraPosition)
            ItemKind.WallDecor -> placeOnWall(ray, planes, cameraPosition)
        }

    /**
     * Prefers a detected floor/table surface under the tap. Detected floor patches rarely cover
     * the whole floor (furniture hides it), so if the tap misses them it falls back to the
     * lowest horizontal plane, extended infinitely.
     */
    fun placeOnFloor(ray: Ray, planes: List<PlaneGeom>, cameraPosition: Vec3): PlaceResult {
        val horizontal = planes.filter { it.kind == PlaneKind.HorizontalUp }
        if (horizontal.isEmpty()) {
            return PlaceResult.Failed("No floor was detected in this photo.")
        }
        val hit = nearestHit(ray, horizontal, requireInside = true)
            ?: horizontal.minBy { it.point.y }.let { floor ->
                ray.intersectPlane(floor.point, floor.normal)?.let { t -> floor to ray.at(t) }
            }
            ?: return PlaceResult.Failed("Tap on the floor (below the horizon).")
        val (plane, point) = hit
        return PlaceResult.Placed(PlacementHit(floorTransform(point, faceToward(point, cameraPosition)), plane))
    }

    fun placeOnWall(ray: Ray, planes: List<PlaneGeom>, cameraPosition: Vec3): PlaceResult {
        val walls = planes.filter { it.kind == PlaneKind.Vertical }
        if (walls.isEmpty()) {
            return PlaceResult.Failed("No wall was detected in this photo.")
        }
        val (plane, point) = nearestHit(ray, walls, requireInside = true)
            ?: nearestHit(ray, walls, requireInside = false)
            ?: return PlaceResult.Failed("Tap on a wall.")
        return PlaceResult.Placed(PlacementHit(wallTransform(point, plane.normal, cameraPosition), plane))
    }

    private fun nearestHit(ray: Ray, planes: List<PlaneGeom>, requireInside: Boolean): Pair<PlaneGeom, Vec3>? =
        planes.mapNotNull { plane ->
            val t = ray.intersectPlane(plane.point, plane.normal) ?: return@mapNotNull null
            val p = ray.at(t)
            if (requireInside && !plane.contains(p)) null else Triple(plane, p, t)
        }.minByOrNull { it.third }?.let { it.first to it.second }

    /** Yaw that turns an item's front (+Z) toward [target], horizontally. */
    fun faceToward(position: Vec3, target: Vec3): Float {
        val d = target - position
        return atan2(d.x, d.z)
    }

    fun floorTransform(position: Vec3, yaw: Float): Mat4 =
        Mat4.translation(position) * Mat4.rotationY(yaw)

    /**
     * Upright frame on a wall: +Z along the wall normal, flipped to face the camera; +Y is world
     * up even if the detected plane leans slightly.
     */
    fun wallTransform(position: Vec3, wallNormal: Vec3, cameraPosition: Vec3): Mat4 {
        var z = Vec3(wallNormal.x, 0f, wallNormal.z).normalized()
        if (z.length() < 0.5f) z = Vec3(0f, 0f, 1f)
        if ((cameraPosition - position) dot z < 0) z = -z
        val y = Vec3.UP
        val x = (y cross z).normalized()
        return Mat4.fromBasis(x, y, z, position)
    }

    /** Rotate an item about its own vertical axis. */
    fun rotateYaw(transform: Mat4, radians: Float): Mat4 = transform * Mat4.rotationY(radians)

    /**
     * Plane an item slides on while dragged: furniture stays at its floor height, decor stays on
     * its wall.
     */
    fun dragPlane(kind: ItemKind, transform: Mat4): Pair<Vec3, Vec3> = when (kind) {
        ItemKind.Furniture -> transform.origin to Vec3.UP
        ItemKind.WallDecor -> transform.origin to transform.axisZ
    }

    /** Move an item so the point grabbed at [grabOffset] (world, relative to origin) follows [ray]. */
    fun drag(kind: ItemKind, transform: Mat4, grabOffset: Vec3, ray: Ray): Mat4? {
        val (point, normal) = dragPlane(kind, transform)
        val t = ray.intersectPlane(point + grabOffset, normal) ?: return null
        return transform.withOrigin(ray.at(t) - grabOffset)
    }

    /** World-space corners: 8 for a box, 4 for decor (TL, TR, BR, BL as seen from the front). */
    fun corners(kind: ItemKind, size: ItemSize, transform: Mat4): List<Vec3> {
        val hw = size.width / 2
        return when (kind) {
            ItemKind.Furniture -> {
                val hd = size.depth / 2
                val h = size.height
                listOf(
                    Vec3(-hw, 0f, -hd), Vec3(hw, 0f, -hd), Vec3(hw, 0f, hd), Vec3(-hw, 0f, hd),
                    Vec3(-hw, h, -hd), Vec3(hw, h, -hd), Vec3(hw, h, hd), Vec3(-hw, h, hd),
                )
            }
            ItemKind.WallDecor -> {
                val hh = size.height / 2
                // A hair off the wall so it isn't coplanar with anything drawn on the wall.
                val z = 0.005f
                listOf(Vec3(-hw, hh, z), Vec3(hw, hh, z), Vec3(hw, -hh, z), Vec3(-hw, -hh, z))
            }
        }.map(transform::transformPoint)
    }

    /** Box faces as indices into [corners], each wound outward. */
    val BOX_FACES: List<IntArray> = listOf(
        intArrayOf(0, 1, 2, 3), // bottom
        intArrayOf(4, 7, 6, 5), // top
        intArrayOf(0, 4, 5, 1), // back
        intArrayOf(3, 2, 6, 7), // front
        intArrayOf(0, 3, 7, 4), // left
        intArrayOf(1, 5, 6, 2), // right
    )
}
