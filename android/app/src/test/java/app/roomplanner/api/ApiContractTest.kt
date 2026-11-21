package app.roomplanner.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the JSON shape the server expects (server/app/schemas.py, docs/api.md). */
class ApiContractTest {

    private val identity = listOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test
    fun snapshotMetaUsesServerFieldNames() {
        val meta = SnapshotMeta(
            pose = identity,
            intrinsics = Intrinsics(1000f, 1000f, 640f, 360f, 1280, 720),
            planes = listOf(
                PlaneData(
                    id = "1",
                    type = "horizontal_up",
                    centerPose = identity,
                    extentX = 3f,
                    extentZ = 4f,
                    polygon = listOf(listOf(-1f, -2f), listOf(1f, -2f), listOf(1f, 2f)),
                ),
            ),
            depthMeta = DepthMeta("uint16_le_mm", 160, 90),
        )
        val json = ApiJson.parseToJsonElement(ApiJson.encodeToString(meta)).jsonObject

        assertEquals(
            setOf("pose", "intrinsics", "planes", "depth_meta", "capture_source"),
            json.keys,
        )
        assertEquals("\"arcore\"", json["capture_source"].toString())
        assertEquals(16, json["pose"]!!.jsonArray.size)
        val plane = json["planes"]!!.jsonArray[0] as JsonObject
        assertEquals(
            setOf("id", "type", "center_pose", "extent_x", "extent_z", "polygon"),
            plane.keys, // null label is omitted, not sent as null
        )
    }

    @Test
    fun snapshotWithoutDepthOmitsDepthMeta() {
        val meta = SnapshotMeta(
            pose = identity,
            intrinsics = Intrinsics(1f, 1f, 0f, 0f, 1, 1),
        )
        val json = ApiJson.parseToJsonElement(ApiJson.encodeToString(meta)).jsonObject
        assertFalse("depth_meta" in json)
    }

    @Test
    fun decodesServerResponsesIgnoringExtraFields() {
        val created = ApiJson.decodeFromString<Snapshot>(
            """{"id":3,"room_id":1,"image_url":"/media/snapshots/a.jpg","depth_url":null,
               "pose":[],"planes":[],"created_at":"2026-11-21T00:00:00Z"}""",
        )
        assertEquals(3L, created.id)
        assertNull(created.depthUrl)
    }

    @Test
    fun parsesTypedServerAddresses() {
        assertEquals("http://192.168.1.20:8000/", RoomPlannerApi.parseBaseUrl("192.168.1.20:8000").toString())
        assertEquals("http://arrbox:8000/", RoomPlannerApi.parseBaseUrl(" http://arrbox:8000/ ").toString())
        assertEquals("https://plan.example/", RoomPlannerApi.parseBaseUrl("https://plan.example").toString())
        assertNull(RoomPlannerApi.parseBaseUrl("   "))
        assertNull(RoomPlannerApi.parseBaseUrl("not a url at all"))
    }
}
