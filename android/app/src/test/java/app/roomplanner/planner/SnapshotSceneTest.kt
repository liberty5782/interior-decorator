package app.roomplanner.planner

import app.roomplanner.api.Intrinsics
import app.roomplanner.api.Item
import app.roomplanner.api.ItemKinds
import app.roomplanner.api.Layout
import app.roomplanner.api.PlaneData
import app.roomplanner.api.Snapshot
import app.roomplanner.geometry.Mat4
import app.roomplanner.geometry.PlacementRules
import app.roomplanner.geometry.PlaneKind
import app.roomplanner.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotSceneTest {

    private fun lookingDown(): List<Float> {
        val a = -0.5f
        val c = cos(a)
        val s = sin(a)
        val rot = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f))
        return (Mat4.translation(Vec3(0f, 1.5f, 0f)) * rot).toList()
    }

    private fun snapshot(id: Long, session: String?) = Snapshot(
        id = id,
        roomId = 1,
        imageUrl = "/media/$id.jpg",
        pose = lookingDown(),
        intrinsics = Intrinsics(1000f, 1000f, 640f, 360f, 1280, 720),
        planes = listOf(
            PlaneData(
                type = "horizontal_up",
                centerPose = Mat4.IDENTITY.toList(),
                extentX = 4f,
                extentZ = 6f,
                polygon = listOf(listOf(-2f, -3f), listOf(2f, -3f), listOf(2f, 3f), listOf(-2f, 3f)),
            ),
        ),
        sessionId = session,
    )

    private val sofa = Item(1, "Sofa", ItemKinds.FURNITURE, 2f, 0.9f, 0.8f)

    @Test
    fun placementsShowOnlyInTheirOwnSession() {
        val sessionOf = mapOf(10L to "a", 11L to "a", 12L to "b", 13L to null)
        val onTen = EditPlacement(1, 1, Mat4.IDENTITY, snapshotId = 10)
        val noSnapshot = EditPlacement(2, 1, Mat4.IDENTITY, snapshotId = null)

        assertTrue(SnapshotScene(snapshot(10, "a")).isVisible(onTen, sessionOf))
        assertTrue("same session", SnapshotScene(snapshot(11, "a")).isVisible(onTen, sessionOf))
        assertFalse("other session", SnapshotScene(snapshot(12, "b")).isVisible(onTen, sessionOf))
        assertFalse("unknown session", SnapshotScene(snapshot(13, null)).isVisible(onTen, sessionOf))
        assertFalse(SnapshotScene(snapshot(10, "a")).isVisible(noSnapshot, sessionOf))
    }

    @Test
    fun convertsServerPlanes() {
        val scene = SnapshotScene(snapshot(1, "a"))
        val floor = scene.planes.single()
        assertEquals(PlaneKind.HorizontalUp, floor.kind)
        assertEquals(4, floor.polygon!!.size)
        assertTrue(floor.contains(Vec3(1f, 0f, 2f)))
        assertEquals(0, scene.uprightRotation)
    }

    @Test
    fun placedItemProjectsUnderTheTapAndCanBeHit() {
        val scene = SnapshotScene(snapshot(1, "a"))
        val fit = scene.fit(1280f, 720f)
        val ray = scene.rayAt(fit, 640f, 500f)
        val t = PlacementRules.placeOnFloor(ray, scene.planes, scene.camera.position)
        val transform = (t as app.roomplanner.geometry.PlaceResult.Placed).hit.transform
        val p = EditPlacement(1, sofa.id, transform, 1)

        val si = scene.project(p, sofa, fit)
        assertNotNull(si)
        // The footprint centre lands where we tapped.
        val bottom = si!!.corners.take(4)
        assertEquals(640f, bottom.map { it.x }.average().toFloat(), 25f)
        assertEquals(500f, bottom.map { it.y }.average().toFloat(), 25f)

        assertEquals(p, scene.hitTest(listOf(si), 640f, 480f)?.placement)
        assertNull(scene.hitTest(listOf(si), 50f, 50f))
    }

    @Test
    fun itemsBehindTheCameraAreNotProjected() {
        val scene = SnapshotScene(snapshot(1, "a"))
        val behind = EditPlacement(1, sofa.id, Mat4.translation(Vec3(0f, 0f, 3f)), 1)
        assertNull(scene.project(behind, sofa, scene.fit(1280f, 720f)))
    }

    @Test
    fun snapshotsWithoutCameraDataCantBePlanned() {
        assertFalse(SnapshotScene.canPlan(Snapshot(1, 1, "/x.jpg")))
        assertTrue(SnapshotScene.canPlan(snapshot(1, null)))
    }

    @Test
    fun suggestsTheNextOptionName() {
        fun l(name: String) = Layout(1, 1, name)
        assertEquals("Option A", nextOptionName(emptyList()))
        assertEquals("Option C", nextOptionName(listOf(l("Option A"), l("Option B"))))
        assertEquals("Option A", nextOptionName(listOf(l("Cosy"))))
    }
}
