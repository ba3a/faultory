package com.faultory.core.scenery

/**
 * Parses the `#RRGGBB` / `#RRGGBBAA` colours scenery content is authored in. Shared by the game and
 * the editor's validator, so what the editor accepts is exactly what the game can draw.
 */
object SceneryColor {
    /** The colour as packed RGBA8888 (LibGDX's `Color.rgba8888` layout), or null when malformed. */
    fun parseOrNull(text: String): Int? {
        if (!text.startsWith("#")) return null
        val hex = text.substring(1)
        if (hex.length != RGB_DIGITS && hex.length != RGBA_DIGITS) return null
        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val value = hex.toLong(radix = 16)
        val rgba = if (hex.length == RGB_DIGITS) (value shl BITS_PER_CHANNEL) or OPAQUE_ALPHA else value
        return rgba.toInt()
    }

    private const val RGB_DIGITS = 6
    private const val RGBA_DIGITS = 8
    private const val BITS_PER_CHANNEL = 8
    private const val OPAQUE_ALPHA = 0xFFL
}
