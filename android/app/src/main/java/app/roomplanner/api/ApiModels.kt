package app.roomplanner.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Mirrors the server's schemas (server/app/schemas.py). See docs/api.md for conventions:
// metres, +Y up, 4x4 matrices as 16 floats in column-major order.

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Health(val status: String, val version: String)

@Serializable
data class Room(val id: Long, val name: String)

@Serializable
data class RoomCreate(val name: String)

@Serializable
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
)

@Serializable
data class PlaneData(
    val id: String? = null,
    /** "horizontal_up" | "horizontal_down" | "vertical" */
    val type: String,
    @SerialName("center_pose") val centerPose: List<Float>,
    @SerialName("extent_x") val extentX: Float,
    @SerialName("extent_z") val extentZ: Float,
    /** Boundary as (x, z) pairs in the plane's local frame. */
    val polygon: List<List<Float>>? = null,
    val label: String? = null,
)

@Serializable
data class DepthMeta(
    /** "uint16_le_mm": tightly packed little-endian uint16 millimetres, row-major. */
    val format: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class SnapshotMeta(
    /** World-from-camera pose of the camera that took the image. */
    val pose: List<Float>,
    val intrinsics: Intrinsics,
    val planes: List<PlaneData> = emptyList(),
    @SerialName("depth_meta") val depthMeta: DepthMeta? = null,
    @SerialName("capture_source") val captureSource: String = "arcore",
)

@Serializable
data class SnapshotCreated(
    val id: Long,
    @SerialName("room_id") val roomId: Long,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("depth_url") val depthUrl: String? = null,
)
