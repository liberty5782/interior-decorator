package app.roomplanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import app.roomplanner.api.RoomPlannerApi
import java.io.ByteArrayOutputStream

/**
 * Loads a picked image (any format the phone can decode, including HEIC) and re-encodes it as a
 * JPEG of at most [maxSide] px, which the server accepts. Transparent areas become white.
 * Call off the main thread.
 */
fun importImageAsJpeg(context: Context, uri: Uri, maxSide: Int = 1600): Pair<Bitmap, ByteArray> {
    val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder also applies EXIF rotation, unlike BitmapFactory.
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read the picked image")
        RoomPlannerApi.decodeDownsampled(bytes, maxSide) ?: error("Not an image")
    }
    val opaque = createBitmap(decoded.width, decoded.height)
    Canvas(opaque).apply {
        drawColor(Color.WHITE)
        drawBitmap(decoded, 0f, 0f, null)
    }
    val out = ByteArrayOutputStream()
    opaque.compress(Bitmap.CompressFormat.JPEG, 90, out)
    return opaque to out.toByteArray()
}
