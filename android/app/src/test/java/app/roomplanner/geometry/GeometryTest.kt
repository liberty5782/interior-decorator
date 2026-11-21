package app.roomplanner.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeometryTest {

    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-3f) {
        if ((expected - actual).length() > eps) fail("expected $expected but was $actual")
    }

    private fun rotX(rad: Float): Mat4 {
        val c = cos(rad)
        val s = sin(rad)
        return Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f))
    }

    private fun rotZ(rad: Float): Mat4 {
        val c = cos(rad)
        val s = sin(rad)
        return Mat4(floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
    }

    private fun camera(pose: Mat4) =
        SnapshotCamera(pose, fx = 1000f, fy = 1000f, cx = 640f, cy = 360f, width = 1280, height = 720)

    private val floor = PlaneGeom(PlaneKind.HorizontalUp, Mat4.IDENTITY, 4f, 4f, null)

    /** Wall facing +Z at z = -3: local +Y (the normal) maps to world +Z. */
    private val wall = PlaneGeom(
        PlaneKind.Vertical,
        Mat4.fromBasis(Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, -1f, 0f), Vec3(0f, 1.5f, -3f)),
        4f, 3f, null,
    )

    // ------------------------------------------------------------- matrices

    @Test
    fun columnMajorLayoutKeepsTranslationAt12To14() {
        val t = Mat4.translation(Vec3(1f, 2f, 3f))
        assertEquals(listOf(1f, 2f, 3f), t.toList().subList(12, 15))
        assertVec(Vec3(1f, 2f, 3f), t.transformPoint(Vec3.ZERO))
    }

    @Test
    fun rigidInverseUndoesTransform() {
        val t = Mat4.translation(Vec3(1f, -2f, 0.5f)) * Mat4.rotationY(0.7f) * rotX(0.3f)
        assertTrue((t * t.inverseRigid()).approxEquals(Mat4.IDENTITY))
        assertTrue((t.inverseRigid() * t).approxEquals(Mat4.IDENTITY))
    }

    @Test
    fun rotationYTurnsFrontTowardPlusX() {
        assertVec(Vec3(1f, 0f, 0f), Mat4.rotationY((PI / 2).toFloat()).transformDir(Vec3(0f, 0f, 1f)))
    }

    // ------------------------------------------------------------- camera

    @Test
    fun projectsAndCastsRaysLikeAPinhole() {
        val cam = camera(Mat4.IDENTITY)
        val center = cam.project(Vec3(0f, 0f, -2f))!!
        assertEquals(640f, center.u, 1e-3f)
        assertEquals(360f, center.v, 1e-3f)
        assertEquals(2f, center.depth, 1e-3f)

        // +X is right, +Y is up (so v decreases).
        val p = cam.project(Vec3(1f, 0.5f, -2f))!!
        assertEquals(640f + 500f, p.u, 1e-3f)
        assertEquals(360f - 250f, p.v, 1e-3f)

        assertNull("behind the camera", cam.project(Vec3(0f, 0f, 1f)))

        val ray = cam.ray(p.u, p.v)
        val t = ray.intersectPlane(Vec3(0f, 0f, -2f), Vec3(0f, 0f, 1f))!!
        assertVec(Vec3(1f, 0.5f, -2f), ray.at(t))
    }

    @Test
    fun projectionRoundTripsForAMovedCamera() {
        val cam = camera(Mat4.translation(Vec3(0.3f, 1.4f, 2f)) * Mat4.rotationY(0.4f) * rotX(-0.35f))
        val world = Vec3(0.2f, 0.4f, -1f)
        val p = cam.project(world)!!
        val ray = cam.ray(p.u, p.v)
        assertVec(world, ray.at(p.depth / (-(cam.toCamera(ray.at(1f)).z))))
    }

    @Test
    fun uprightRotationComesFromThePose() {
        assertEquals(0, camera(Mat4.IDENTITY).uprightRotationDegrees())
        // Rolled so world-up appears on the image's right: turn the image 270° clockwise.
        assertEquals(270, camera(rotZ((PI / 2).toFloat())).uprightRotationDegrees())
        // World-up on the image's left: 90° clockwise.
        assertEquals(90, camera(rotZ((-PI / 2).toFloat())).uprightRotationDegrees())
        assertEquals(180, camera(rotZ(PI.toFloat())).uprightRotationDegrees())
        // Looking steeply down still resolves sensibly.
        assertEquals(0, camera(rotX(-1.2f)).uprightRotationDegrees())
    }

    // ------------------------------------------------------------- image fit

    @Test
    fun imageFitRoundTripsInEveryRotation() {
        for (rot in listOf(0, 90, 180, 270)) {
            val fit = ImageFit(1280, 720, rot, viewWidth = 1080f, viewHeight = 1920f)
            for ((u, v) in listOf(0f to 0f, 1280f to 720f, 100f to 600f, 640f to 360f)) {
                val s = fit.map(u, v)
                val back = fit.unmap(s.x, s.y)
                assertEquals("rot $rot u", u, back.x, 1e-2f)
                assertEquals("rot $rot v", v, back.y, 1e-2f)
                assertTrue("rot $rot inside view", s.x in -0.5f..1080.5f && s.y in -0.5f..1920.5f)
            }
            // The affine matrix used to draw the bitmap agrees with map().
            val a = fit.affineValues()
            val s = fit.map(321f, 123f)
            assertEquals(s.x, a[0] * 321f + a[1] * 123f + a[2], 1e-2f)
            assertEquals(s.y, a[3] * 321f + a[4] * 123f + a[5], 1e-2f)
        }
    }

    @Test
    fun quarterTurnMovesTopLeftToTopRight() {
        val fit = ImageFit(1280, 720, 90, viewWidth = 720f, viewHeight = 1280f)
        val tl = fit.map(0f, 0f)
        assertEquals(720f, tl.x, 1e-2f)
        assertEquals(0f, tl.y, 1e-2f)
    }

    // ------------------------------------------------------------- placement

    @Test
    fun furnitureLandsOnTheFloorFacingTheCamera() {
        // Standing at 1.5 m, looking 30° down.
        val cam = camera(Mat4.translation(Vec3(0f, 1.5f, 0f)) * rotX(-0.5236f))
        val ray = cam.ray(640f, 360f)
        val result = PlacementRules.placeOnFloor(ray, listOf(floor), cam.position)
        val t = (result as PlaceResult.Placed).hit.transform
        assertEquals(0f, t.origin.y, 1e-3f)
        assertEquals(-1.5f / kotlin.math.tan(0.5236f), t.origin.z, 1e-2f)
        // Front (+Z) points back toward the camera.
        assertVec(Vec3(0f, 0f, 1f), t.axisZ)
        assertVec(Vec3.UP, t.axisY)
    }

    @Test
    fun floorFallsBackToTheLowestPlaneOutsideDetectedPatches() {
        val smallFloor = PlaneGeom(PlaneKind.HorizontalUp, Mat4.IDENTITY, 0.5f, 0.5f, null)
        val table = PlaneGeom(PlaneKind.HorizontalUp, Mat4.translation(Vec3(3f, 0.75f, -3f)), 1f, 1f, null)
        val cam = camera(Mat4.translation(Vec3(0f, 1.5f, 0f)) * rotX(-0.5236f))
        val result = PlacementRules.placeOnFloor(cam.ray(640f, 360f), listOf(table, smallFloor), cam.position)
        assertEquals(0f, (result as PlaceResult.Placed).hit.transform.origin.y, 1e-3f)
    }

    @Test
    fun furnitureNeedsAFloor() {
        val cam = camera(Mat4.IDENTITY)
        assertTrue(PlacementRules.placeOnFloor(cam.ray(640f, 360f), listOf(wall), cam.position) is PlaceResult.Failed)
        // Looking at the horizon or above never hits the floor.
        val up = camera(Mat4.translation(Vec3(0f, 1.5f, 0f)) * rotX(0.3f))
        assertTrue(PlacementRules.placeOnFloor(up.ray(640f, 360f), listOf(floor), up.position) is PlaceResult.Failed)
    }

    @Test
    fun decorHangsOnTheWallFacingOut() {
        val cam = camera(Mat4.translation(Vec3(0f, 1.5f, 0f)))
        val result = PlacementRules.placeOnWall(cam.ray(640f, 360f), listOf(floor, wall), cam.position)
        val t = (result as PlaceResult.Placed).hit.transform
        assertVec(Vec3(0f, 1.5f, -3f), t.origin)
        assertVec(Vec3(0f, 0f, 1f), t.axisZ)
        assertVec(Vec3.UP, t.axisY)
        assertVec(Vec3(1f, 0f, 0f), t.axisX)
    }

    @Test
    fun wallFrameFlipsToFaceTheCameraWhateverTheNormal() {
        val t = PlacementRules.wallTransform(Vec3(0f, 1.5f, -3f), Vec3(0f, 0f, -1f), Vec3(0f, 1.5f, 0f))
        assertVec(Vec3(0f, 0f, 1f), t.axisZ)
        assertTrue((t * t.inverseRigid()).approxEquals(Mat4.IDENTITY))
    }

    @Test
    fun draggingFurnitureKeepsItOnItsFloor() {
        val cam = camera(Mat4.translation(Vec3(0f, 1.5f, 0f)) * rotX(-0.5236f))
        val start = PlacementRules.floorTransform(Vec3(0f, 0f, -2.6f), 0.3f)
        val grabRay = cam.ray(640f, 360f)
        val (p, n) = PlacementRules.dragPlane(ItemKind.Furniture, start)
        val grab = grabRay.at(grabRay.intersectPlane(p, n)!!) - start.origin
        val moved = PlacementRules.drag(ItemKind.Furniture, start, grab, cam.ray(900f, 500f))
        assertNotNull(moved)
        assertEquals(0f, moved!!.origin.y, 1e-3f)
        assertTrue("moved right", moved.origin.x > 0.1f)
        // Rotation untouched.
        assertVec(start.axisZ, moved.axisZ)
    }

    @Test
    fun rotatingYawSpinsAboutTheItemsOwnOrigin() {
        val start = PlacementRules.floorTransform(Vec3(1f, 0f, -2f), 0f)
        val turned = PlacementRules.rotateYaw(start, (PI / 2).toFloat())
        assertVec(start.origin, turned.origin)
        assertVec(Vec3(1f, 0f, 0f), turned.axisZ)
    }

    @Test
    fun boxCornersSitOnTheFloor() {
        val t = PlacementRules.floorTransform(Vec3(1f, 0f, -2f), 0f)
        val c = PlacementRules.corners(ItemKind.Furniture, ItemSize(2f, 1f, 0.8f), t)
        assertEquals(8, c.size)
        assertTrue(c.take(4).all { abs(it.y) < 1e-4f })
        assertTrue(c.drop(4).all { abs(it.y - 0.8f) < 1e-4f })
        assertEquals(2f, c.maxOf { it.x } - c.minOf { it.x }, 1e-4f)
    }

    // ------------------------------------------------------------- 2D helpers

    @Test
    fun polygonAndHull() {
        val square = listOf(Point2(0f, 0f), Point2(2f, 0f), Point2(2f, 2f), Point2(0f, 2f))
        assertTrue(pointInPolygon(Point2(1f, 1f), square))
        assertFalse(pointInPolygon(Point2(3f, 1f), square))
        val hull = convexHull(square + Point2(1f, 1f) + Point2(0.5f, 1.5f))
        assertEquals(4, hull.size)
    }

    @Test
    fun planeContainmentUsesThePolygonWhenPresent() {
        val tri = PlaneGeom(
            PlaneKind.HorizontalUp, Mat4.IDENTITY, 10f, 10f,
            listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(0f, 1f)),
        )
        assertTrue(tri.contains(Vec3(0.2f, 0f, 0.2f)))
        assertFalse(tri.contains(Vec3(0.8f, 0f, 0.8f)))
    }
}
