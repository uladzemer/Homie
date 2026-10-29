package ru.iptvsimple.home

import kotlin.test.Test
import kotlin.test.assertEquals

class WallpaperSizeTest {
    @Test
    fun decodesFor720pAnd1080pWithoutDoubleSampling() {
        assertEquals(WallpaperSize(1, 1920, 1280), wallpaperSize(1920, 1080, 1280, 720))
        assertEquals(WallpaperSize(1, 1920, 1920), wallpaperSize(1920, 1080, 1920, 1080))
        assertEquals(WallpaperSize(2, 960, 960), wallpaperSize(1920, 1080, 960, 540))
        assertEquals(WallpaperSize(4, 1920, 1280), wallpaperSize(7680, 4320, 1280, 720))
    }

    @Test
    fun cropCoversBothAxesAndDoesNotUpscaleSmallPhotos() {
        assertEquals(WallpaperSize(1, 1920, 1440), wallpaperSize(1920, 1080, 1024, 810))
        assertEquals(WallpaperSize(1, 640, 640), wallpaperSize(640, 360, 1280, 720))
    }
}
