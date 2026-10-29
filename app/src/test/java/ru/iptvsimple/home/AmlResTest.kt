package ru.iptvsimple.home

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Синтетический раздел с двумя картинками и свободным хвостом, без Android. */
class AmlResTest {
    private val bootup = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
    private val other = byteArrayOf(11, 12, 13, 14, 15)

    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun sign(bytes: ByteArray) {
        val b = buffer(bytes)
        b.putInt(0, CRC32().also { it.update(bytes, 4, b.getInt(16) - 4) }.value.toInt().inv())
    }

    private fun image(): ByteArray = ByteArray(1024).also { bytes ->
        val b = buffer(bytes)
        b.putInt(4, 2)
        "AML_RES!".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        b.putInt(16, 256 + other.size)
        b.putInt(20, 2)
        b.putInt(24, 64)
        for ((index, entry) in listOf("bootup" to bootup, "other" to other).withIndex()) {
            val offset = 0x40 + index * 0x40
            val start = 192 + index * 64
            b.putInt(offset, 0x12345678)
            b.putInt(offset + 8, entry.second.size)
            b.putInt(offset + 12, start)
            entry.first.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset + 0x20)
            entry.second.copyInto(bytes, start)
        }
        sign(bytes)
    }

    @Test fun validatesCompleteImageWithoutChangingIt() {
        val src = image()
        val original = src.copyOf()
        assertTrue(AmlRes.isValid(src))
        assertContentEquals(original, src)
        assertFalse(AmlRes.isValid(src.also { it[200] = 88 }))
    }

    @Test fun crcUsesComplementAndSkipsStoredChecksumAndPadding() {
        val data = ByteArray(4) { 42 } + "123456789".toByteArray() + byteArrayOf(99)
        assertEquals(0x340bc6d9, AmlRes.crc(data, 13))
        data[0] = 0
        data[13] = 0
        assertEquals(0x340bc6d9, AmlRes.crc(data, 13))
    }

    @Test fun refusesTruncatedHeaderAndWrongFormat() {
        for (size in listOf(0, 7, 16, 63)) {
            assertFalse(AmlRes.isValid(ByteArray(size)))
        }
        for ((offset, value) in listOf(4 to 1, 8 to 0, 16 to 0, 16 to 2048, 20 to 0, 20 to 65, 24 to 0, 24 to -1)) {
            val src = image()
            buffer(src).putInt(offset, value)
            if (offset != 16) sign(src)
            assertFalse(AmlRes.isValid(src), "offset=$offset value=$value")
        }
    }

    @Test fun refusesMalformedTableEvenWithValidCrc() {
        // Переполнение смещений, таблица за концом данных, отрицательная длина и огромное выравнивание.
        for ((offset, value) in listOf(20 to 64, 0x4c to 0, 0x4c to Int.MAX_VALUE, 0x48 to -1, 0x48 to Int.MAX_VALUE, 24 to Int.MAX_VALUE)) {
            val src = image()
            buffer(src).putInt(offset, value)
            sign(src)
            assertFalse(AmlRes.isValid(src), "offset=$offset value=$value")
        }
    }
}
