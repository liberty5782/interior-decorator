package app.roomplanner.api

import app.roomplanner.capture.CapturedSnapshot
import app.roomplanner.geometry.Mat4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the app's API client against a real server, end to end. Skipped unless
 * ROOMPLANNER_TEST_SERVER is set, e.g. `ROOMPLANNER_TEST_SERVER=localhost:8765`.
 * Use a throwaway server: it creates data.
 */
class ServerRoundTripTest {

    private val address = System.getenv("ROOMPLANNER_TEST_SERVER")

    @Test
    fun fullPlanningFlow() = runBlocking {
        assumeTrue("set ROOMPLANNER_TEST_SERVER to run", !address.isNullOrBlank())
        val api = RoomPlannerApi(RoomPlannerApi.parseBaseUrl(address!!)!!)

        assertEquals("ok", api.health().status)
        val room = api.createRoom("Round trip")

        val meta = SnapshotMeta(
            pose = Mat4.IDENTITY.toList(),
            intrinsics = Intrinsics(500f, 500f, 320f, 240f, 640, 480),
            planes = listOf(PlaneData(type = "horizontal_up", centerPose = Mat4.IDENTITY.toList(), extentX = 2f, extentZ = 2f)),
            depthMeta = DepthMeta("uint16_le_mm", 4, 2),
            sessionId = "session-1",
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        val snap = api.uploadSnapshot(room.id, CapturedSnapshot(jpeg, ByteArray(16), meta))
        assertEquals("session-1", snap.sessionId)
        assertEquals(1, api.listSnapshots(room.id).single().planes.size)

        val sofa = api.createItem(ItemFields("Sofa", ItemKinds.FURNITURE, 2.1f, 0.9f, 0.8f, price = 499f))
        // PATCH with every field: clearing the price must work.
        val updated = api.updateItem(sofa.id, ItemFields("Sofa", ItemKinds.FURNITURE, 2.2f, 0.9f, 0.8f, price = null))
        assertEquals(2.2f, updated.widthM, 1e-5f)
        assertNull(updated.price)

        val a = api.createLayout(room.id, "Option A")
        val moved = Mat4.translation(app.roomplanner.geometry.Vec3(1f, 0f, -2f)).toList()
        val saved = api.savePlacements(a.id, listOf(PlacementCreate(sofa.id, moved, snap.id)))
        assertEquals(moved, saved.placements.single().transform)

        val b = api.duplicateLayout(a.id, "Option B")
        api.savePlacements(b.id, emptyList())
        assertEquals(1, api.getLayout(a.id).placements.size)
        assertEquals(0, api.getLayout(b.id).placements.size)

        // In use by Option A, so it can't be deleted yet.
        val conflict = runCatching { api.deleteItem(sofa.id) }.exceptionOrNull() as ApiException
        assertEquals(409, conflict.code)
        api.deleteLayout(a.id)
        api.deleteItem(sofa.id)
        assertEquals(listOf("Option B"), api.listLayouts(room.id).map { it.name })
    }
}
