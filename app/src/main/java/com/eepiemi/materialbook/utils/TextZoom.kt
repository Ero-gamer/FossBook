package com.eepiemi.materialbook.utils

/**
 * Values of the "Text size" setting: the pages' text zoom in percent (WebSettings.textZoom),
 * stored as strings like the app's other choice settings.
 */
object TextZoom {
    const val DEFAULT = "100"
    val ALL = listOf("85", "100", "115", "130", "150")

    /** The stored value as a percentage; an unknown value counts as the default. */
    fun percent(stored: String): Int = stored.takeIf { it in ALL }?.toInt() ?: DEFAULT.toInt()
}
