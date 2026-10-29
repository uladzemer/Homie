package ru.iptvsimple.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootImageTest {
    @Test fun keepsSmallImagesAndSamplesAtPowerOfTwoBoundaries() {
        assertEquals(1, BootImage.sampleSize(1, 1))
        assertEquals(1, BootImage.sampleSize(840, 440))
        assertEquals(2, BootImage.sampleSize(841, 440))
        assertEquals(2, BootImage.sampleSize(840, 441))
        assertEquals(2, BootImage.sampleSize(1680, 880))
        assertEquals(4, BootImage.sampleSize(1681, 880))
    }

    @Test fun boundsDecodedDimensionsForPortraitLandscapeAndPanorama() {
        for ((width, height) in listOf(4000 to 3000, 3000 to 4000, 32768 to 1, 1 to 32768, 10000 to 10000)) {
            val sample = BootImage.sampleSize(width, height)!!
            assertEquals(0, sample and (sample - 1))
            assertTrue((width + sample - 1) / sample <= 840)
            assertTrue((height + sample - 1) / sample <= 440)
        }
    }

    @Test fun rejectsUnknownAbsurdAndOverflowingDimensions() {
        for ((width, height) in listOf(-1 to -1, 0 to 100, 100 to 0, 32769 to 1, 10001 to 10000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertNull(BootImage.sampleSize(width, height))
        }
    }
}
