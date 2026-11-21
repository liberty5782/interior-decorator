package app.roomplanner.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.roomplanner.capture.CapturedSnapshot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, message: String) : Exception(message)

/** Thin client for the Room Planner backend. All calls are main-safe. */
class RoomPlannerApi(val base: HttpUrl) {

    suspend fun health(): Health = call(get("health"))

    // ------------------------------------------------------------ rooms

    suspend fun listRooms(): List<Room> = call(get("rooms"))

    suspend fun createRoom(name: String): Room = call(post("rooms", json(NameBody(name))))

    // ------------------------------------------------------------ snapshots

    suspend fun listSnapshots(roomId: Long): List<Snapshot> = call(get("rooms/$roomId/snapshots"))

    suspend fun uploadSnapshot(roomId: Long, snapshot: CapturedSnapshot): Snapshot {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("meta", ApiJson.encodeToString(snapshot.meta))
            .addFormDataPart("image", "frame.jpg", snapshot.jpeg.toRequestBody(JPEG))
            .apply {
                snapshot.depth?.let {
                    addFormDataPart("depth", "depth.bin", it.toRequestBody(OCTET_STREAM))
                }
            }
            .build()
        return call(post("rooms/$roomId/snapshots", body))
    }

    // ------------------------------------------------------------ items

    suspend fun listItems(): List<Item> = call(get("items"))

    suspend fun createItem(fields: ItemFields): Item = call(post("items", json(fields)))

    /** Sends every field, including nulls, so clearing e.g. the price works. */
    suspend fun updateItem(id: Long, fields: ItemFields): Item =
        call(request("items/$id").patch(FullJson.encodeToString(fields).toRequestBody(JSON)).build())

    suspend fun deleteItem(id: Long) = callNoContent(request("items/$id").delete().build())

    suspend fun uploadItemImage(id: Long, jpeg: ByteArray): Item {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "image.jpg", jpeg.toRequestBody(JPEG))
            .build()
        return call(request("items/$id/image").put(body).build())
    }

    // ------------------------------------------------------------ layouts

    suspend fun listLayouts(roomId: Long): List<Layout> = call(get("rooms/$roomId/layouts"))

    suspend fun createLayout(roomId: Long, name: String): Layout =
        call(post("rooms/$roomId/layouts", json(NameBody(name))))

    suspend fun getLayout(id: Long): LayoutDetail = call(get("layouts/$id"))

    suspend fun duplicateLayout(id: Long, name: String): LayoutDetail =
        call(post("layouts/$id/duplicate", json(NameBody(name))))

    suspend fun deleteLayout(id: Long) = callNoContent(request("layouts/$id").delete().build())

    /** Replaces all of a layout's placements atomically. */
    suspend fun savePlacements(layoutId: Long, placements: List<PlacementCreate>): LayoutDetail =
        call(request("layouts/$layoutId/placements").put(json(placements)).build())

    // ------------------------------------------------------------ media

    /** Downloads an image from a server-relative media URL, downsampled to at most [maxSide] px. */
    suspend fun fetchBitmap(mediaUrl: String, maxSide: Int = 2048): Bitmap = withContext(Dispatchers.IO) {
        val url = base.resolve(mediaUrl) ?: throw ApiException(0, "bad media URL $mediaUrl")
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code} for $mediaUrl")
            r.body.bytes()
        }
        decodeDownsampled(bytes, maxSide) ?: throw ApiException(0, "not an image: $mediaUrl")
    }

    // ------------------------------------------------------------ plumbing

    private fun request(path: String) = Request.Builder().url(url(path))

    private fun get(path: String) = request(path).get().build()

    private fun post(path: String, body: RequestBody) = request(path).post(body).build()

    private inline fun <reified T> json(value: T): RequestBody =
        ApiJson.encodeToString(value).toRequestBody(JSON)

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments("api/$path").build()

    private suspend inline fun <reified T> call(request: Request): T = withContext(Dispatchers.IO) {
        ApiJson.decodeFromString<T>(execute(request))
    }

    private suspend fun callNoContent(request: Request) {
        withContext(Dispatchers.IO) { execute(request) }
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(response.code, text))
            text
        }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Like [ApiJson] but writes nulls, for PATCH bodies where null means "clear". */
        private val FullJson = Json(ApiJson) { explicitNulls = true }

        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Accepts what people actually type: "192.168.1.20:8000", "http://arrbox:8000/",
         * "arrbox.lan". Defaults to http:// because the server lives on the LAN.
         */
        fun parseBaseUrl(input: String): HttpUrl? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return withScheme.toHttpUrlOrNull()
        }

        /** FastAPI errors look like {"detail": "..."}; show that rather than raw JSON. */
        internal fun errorMessage(code: Int, body: String): String {
            val detail = runCatching {
                val el = ApiJson.parseToJsonElement(body)
                (el as? kotlinx.serialization.json.JsonObject)?.get("detail")
            }.getOrNull()
            val text = when (detail) {
                is kotlinx.serialization.json.JsonPrimitive -> detail.content
                null -> body.take(300)
                else -> detail.toString().take(300)
            }
            return "HTTP $code: $text"
        }

        fun decodeDownsampled(bytes: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
