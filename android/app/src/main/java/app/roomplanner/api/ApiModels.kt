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
    /** Snapshots with the same session id share one world frame (one ARCore session). */
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class Snapshot(
    val id: Long,
    @SerialName("room_id") val roomId: Long,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("depth_url") val depthUrl: String? = null,
    val pose: List<Float> = emptyList(),
    val intrinsics: Intrinsics? = null,
    val planes: List<PlaneData> = emptyList(),
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ---------------------------------------------------------------- items

object ItemKinds {
    const val FURNITURE = "furniture"
    const val WALL_DECOR = "wall_decor"
}

@Serializable
data class Item(
    val id: Long,
    val name: String,
    /** [ItemKinds.FURNITURE] or [ItemKinds.WALL_DECOR] */
    val kind: String,
    @SerialName("width_m") val widthM: Float,
    @SerialName("depth_m") val depthM: Float,
    @SerialName("height_m") val heightM: Float,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("model_url") val modelUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val price: Float? = null,
    val notes: String? = null,
)

/** Body for creating an item, and (with every field sent) for updating one. */
@Serializable
data class ItemFields(
    val name: String,
    val kind: String,
    @SerialName("width_m") val widthM: Float,
    @SerialName("depth_m") val depthM: Float,
    @SerialName("height_m") val heightM: Float,
    @SerialName("source_url") val sourceUrl: String? = null,
    val price: Float? = null,
    val notes: String? = null,
)

// ---------------------------------------------------------------- layouts

@Serializable
data class Layout(
    val id: Long,
    @SerialName("room_id") val roomId: Long,
    val name: String,
    val notes: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class Placement(
    val id: Long,
    @SerialName("layout_id") val layoutId: Long,
    @SerialName("item_id") val itemId: Long,
    val transform: List<Float>,
    @SerialName("snapshot_id") val snapshotId: Long? = null,
)

@Serializable
data class LayoutDetail(
    val id: Long,
    @SerialName("room_id") val roomId: Long,
    val name: String,
    val notes: String? = null,
    val placements: List<Placement> = emptyList(),
)

@Serializable
data class PlacementCreate(
    @SerialName("item_id") val itemId: Long,
    val transform: List<Float>,
    @SerialName("snapshot_id") val snapshotId: Long? = null,
)

@Serializable
data class NameBody(val name: String)
