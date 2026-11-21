package app.roomplanner.ui

import app.roomplanner.api.Item
import app.roomplanner.api.ItemFields
import app.roomplanner.api.ItemKinds
import kotlin.math.roundToInt

/**
 * Text-field state for the item editor. Dimensions are typed in centimetres (what product pages
 * use) and sent to the server in metres.
 */
data class ItemForm(
    val name: String = "",
    val kind: String = ItemKinds.FURNITURE,
    val widthCm: String = "",
    val depthCm: String = "",
    val heightCm: String = "",
    val sourceUrl: String = "",
    val price: String = "",
    val notes: String = "",
) {
    sealed interface Result {
        data class Ok(val fields: ItemFields) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(): Result {
        val n = name.trim()
        if (n.isEmpty()) return Result.Invalid("Give it a name.")
        val isDecor = kind == ItemKinds.WALL_DECOR
        val w = parseNumber(widthCm)
        val h = parseNumber(heightCm)
        // Wall decor is basically flat, so depth may be left empty (0).
        val d = if (isDecor && depthCm.isBlank()) 0f else parseNumber(depthCm)
        if (w == null || w <= 0f) return Result.Invalid("Width must be a number of centimetres.")
        if (h == null || h <= 0f) return Result.Invalid("Height must be a number of centimetres.")
        if (d == null || d < 0f || (!isDecor && d == 0f)) {
            return Result.Invalid("Depth must be a number of centimetres.")
        }
        if (maxOf(w, d, h) > 5000f) return Result.Invalid("That's over 50 m. Dimensions are in centimetres.")
        val p = if (price.isBlank()) null else parseNumber(price)
        if (price.isNotBlank() && (p == null || p < 0f)) return Result.Invalid("Price must be a number.")
        return Result.Ok(
            ItemFields(
                name = n,
                kind = kind,
                widthM = w / 100f,
                depthM = d / 100f,
                heightM = h / 100f,
                sourceUrl = sourceUrl.trim().ifEmpty { null },
                price = p,
                notes = notes.trim().ifEmpty { null },
            )
        )
    }

    companion object {
        fun from(item: Item) = ItemForm(
            name = item.name,
            kind = item.kind,
            widthCm = formatCm(item.widthM),
            depthCm = formatCm(item.depthM),
            heightCm = formatCm(item.heightM),
            sourceUrl = item.sourceUrl.orEmpty(),
            price = item.price?.let { formatNumber(it) }.orEmpty(),
            notes = item.notes.orEmpty(),
        )

        /** Accepts "120", "120.5", "120,5" and surrounding spaces. */
        fun parseNumber(text: String): Float? =
            text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

        fun formatCm(metres: Float): String = formatNumber(metres * 100f)

        private fun formatNumber(v: Float): String {
            val tenths = (v * 10f).roundToInt()
            return if (tenths % 10 == 0) (tenths / 10).toString() else (tenths / 10f).toString()
        }

        /** "210 × 90 × 80 cm" (W × D × H), or "50 × 70 cm" (W × H) for flat wall decor. */
        fun dimensions(item: Item): String =
            if (item.kind == ItemKinds.WALL_DECOR && item.depthM == 0f) {
                "${formatCm(item.widthM)} × ${formatCm(item.heightM)} cm"
            } else {
                "${formatCm(item.widthM)} × ${formatCm(item.depthM)} × ${formatCm(item.heightM)} cm"
            }
    }
}
