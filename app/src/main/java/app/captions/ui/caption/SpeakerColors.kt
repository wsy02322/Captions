package app.captions.ui.caption

/** Color-blind-friendly speaker palette. Index by speaker id; never show labels. */
object SpeakerColors {
    val Light = listOf(
        0xFF1565C0.toInt(), // blue
        0xFFE65100.toInt(), // orange
        0xFF2E7D32.toInt(), // green
        0xFF6A1B9A.toInt(), // purple
        0xFF00838F.toInt(), // cyan
        0xFFC2185B.toInt(), // pink
        0xFF4527A0.toInt(), // indigo
        0xFF558B2F.toInt(), // lime
    )

    fun colorFor(speaker: Int, darkTheme: Boolean = false): Int {
        val palette = Light
        val base = palette[Math.floorMod(speaker, palette.size)]
        return if (darkTheme) lighten(base) else base
    }

    private fun lighten(color: Int): Int {
        val a = color ushr 24 and 0xFF
        val r = ((color ushr 16 and 0xFF) + 40).coerceAtMost(255)
        val g = ((color ushr 8 and 0xFF) + 40).coerceAtMost(255)
        val b = ((color and 0xFF) + 40).coerceAtMost(255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
