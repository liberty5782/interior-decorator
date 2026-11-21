package app.roomplanner.planner

import app.roomplanner.api.Item
import app.roomplanner.api.ItemKinds
import app.roomplanner.api.PlaneData
import app.roomplanner.api.Snapshot
import app.roomplanner.geometry.ImageFit
import app.roomplanner.geometry.ItemKind
import app.roomplanner.geometry.ItemSize
import app.roomplanner.geometry.Mat4
import app.roomplanner.geometry.PlaneGeom
import app.roomplanner.geometry.PlaneKind
import app.roomplanner.geometry.PlacementRules
import app.roomplanner.geometry.Point2
import app.roomplanner.geometry.SnapshotCamera
import app.roomplanner.geometry.Vec3
import app.roomplanner.geometry.convexHull
import app.roomplanner.geometry.pointInPolygon

/** A placement being edited. [key] is local; the server re-numbers placements on every save. */
data class EditPlacement(
    val key: Long,
    val itemId: Long,
    val transform: Mat4,
    val snapshotId: Long?,
)

fun Item.geomKind() = if (kind == ItemKinds.WALL_DECOR) ItemKind.WallDecor else ItemKind.Furniture

fun Item.size() = ItemSize(widthM, depthM, heightM)

/** An item projected onto the screen. */
class ScreenItem(
    val placement: EditPlacement,
    val item: Item,
    /** Screen points of the corners (see [PlacementRules.corners]). */
    val corners: List<Point2>,
    /** Camera-space depth of each corner, for sorting faces back to front. */
    val depths: List<Float>,
    val hull: List<Point2>,
) {
    val depth = depths.average().toFloat()
}

/**
 * One snapshot, ready to plan on: its camera, planes, and the maths to go between the
 * world, the image and the screen. No Android drawing code, so it's unit-testable.
 */
class SnapshotScene(val snapshot: Snapshot) {

    val camera: SnapshotCamera = run {
        val k = requireNotNull(snapshot.intrinsics) { "snapshot ${snapshot.id} has no intrinsics" }
        require(snapshot.pose.size == 16) { "snapshot ${snapshot.id} has no pose" }
        SnapshotCamera(Mat4.of(snapshot.pose), k.fx, k.fy, k.cx, k.cy, k.width, k.height)
    }

    val planes: List<PlaneGeom> = snapshot.planes.map { it.toGeom() }

    val uprightRotation = camera.uprightRotationDegrees()

    fun fit(viewWidth: Float, viewHeight: Float) =
        ImageFit(camera.width, camera.height, uprightRotation, viewWidth, viewHeight)

    /**
     * Placements only line up on photos from the AR session they were placed in (same world
     * origin). Placements without a known session show only on their own photo.
     */
    fun isVisible(p: EditPlacement, sessionOf: Map<Long, String?>): Boolean {
        if (p.snapshotId == snapshot.id) return true
        val mine = snapshot.sessionId ?: return false
        return p.snapshotId != null && sessionOf[p.snapshotId] == mine
    }

    /** Projects a placement to the screen, or null if any corner is behind the camera. */
    fun project(p: EditPlacement, item: Item, fit: ImageFit): ScreenItem? {
        val world = PlacementRules.corners(item.geomKind(), item.size(), p.transform)
        val projected = world.map { camera.project(it) ?: return null }
        val screen = projected.map { fit.map(it.u, it.v) }
        return ScreenItem(p, item, screen, projected.map { it.depth }, convexHull(screen))
    }

    /** Topmost (nearest) item under a screen point. */
    fun hitTest(items: List<ScreenItem>, x: Float, y: Float): ScreenItem? =
        items.filter { pointInPolygon(Point2(x, y), it.hull) }.minByOrNull { it.depth }

    fun rayAt(fit: ImageFit, x: Float, y: Float) = fit.unmap(x, y).let { camera.ray(it.x, it.y) }

    /** A plane's outline in world space, for showing where things can go. */
    fun outline(plane: PlaneGeom): List<Vec3> {
        val poly = plane.polygon ?: listOf(
            Point2(-plane.extentX / 2, -plane.extentZ / 2), Point2(plane.extentX / 2, -plane.extentZ / 2),
            Point2(plane.extentX / 2, plane.extentZ / 2), Point2(-plane.extentX / 2, plane.extentZ / 2),
        )
        return poly.map { plane.pose.transformPoint(Vec3(it.x, 0f, it.y)) }
    }

    companion object {
        fun canPlan(s: Snapshot) = s.intrinsics != null && s.pose.size == 16

        fun PlaneData.toGeom() = PlaneGeom(
            kind = when (type) {
                "horizontal_up" -> PlaneKind.HorizontalUp
                "horizontal_down" -> PlaneKind.HorizontalDown
                else -> PlaneKind.Vertical
            },
            pose = Mat4.of(centerPose),
            extentX = extentX,
            extentZ = extentZ,
            polygon = polygon?.filter { it.size == 2 }?.map { Point2(it[0], it[1]) },
        )
    }
}
