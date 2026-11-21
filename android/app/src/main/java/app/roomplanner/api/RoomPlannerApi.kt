package app.roomplanner.api

import app.roomplanner.capture.CapturedSnapshot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(message: String) : Exception(message)

/** Thin client for the Room Planner backend. All calls are main-safe. */
class RoomPlannerApi(private val base: HttpUrl) {

    suspend fun health(): Health = get("health")

    suspend fun listRooms(): List<Room> = get("rooms")

    suspend fun createRoom(name: String): Room =
        send("rooms", ApiJson.encodeToString(RoomCreate(name)).toRequestBody(JSON))

    suspend fun uploadSnapshot(roomId: Long, snapshot: CapturedSnapshot): SnapshotCreated {
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
        return send("rooms/$roomId/snapshots", body)
    }

    private suspend inline fun <reified T> get(path: String): T =
        call(Request.Builder().url(url(path)).get().build())

    private suspend inline fun <reified T> send(path: String, body: RequestBody): T =
        call(Request.Builder().url(url(path)).post(body).build())

    private fun url(path: String): HttpUrl =
        base.newBuilder().addPathSegments("api/$path").build()

    private suspend inline fun <reified T> call(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw ApiException("HTTP ${response.code}: ${text.take(300)}")
            }
            ApiJson.decodeFromString<T>(text)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

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
    }
}
