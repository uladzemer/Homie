package ru.iptvsimple.home

import kotlin.math.roundToInt

internal data class WallpaperSize(val sample: Int, val density: Int, val targetDensity: Int)

/** Масштаб Crop без увеличения исходника; JPEG сначала прореживается, затем доводится до экрана. */
internal fun wallpaperSize(sourceWidth: Int, sourceHeight: Int, screenWidth: Int, screenHeight: Int): WallpaperSize {
    val scale = maxOf(screenWidth.coerceAtLeast(1).toFloat() / sourceWidth, screenHeight.coerceAtLeast(1).toFloat() / sourceHeight).coerceAtMost(1f)
    val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    var sample = 1
    while (sourceWidth / sample / 2 >= width && sourceHeight / sample / 2 >= height) sample *= 2
    return WallpaperSize(sample, sourceWidth / sample, width)
}
