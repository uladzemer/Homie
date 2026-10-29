package ru.iptvsimple.home

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Только проверка старой копии AML_RES_IMG v2; новые образы загрузчика не создаём. */
internal object AmlRes {
    fun isValid(src: ByteArray): Boolean {
        if (src.size < 0x40) return false
        val b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        if (String(src, 8, 8, Charsets.US_ASCII) != "AML_RES!" || b.getInt(4) != 2) return false
        val size = b.getInt(16)
        val count = b.getInt(20)
        val align = b.getInt(24)
        if (size !in 0x40..src.size || count !in 1..64 || align !in 1..src.size || b.getInt(0) != crc(src, size)) return false
        val header = 0x40 + count * 0x40
        if (header > size) return false
        for (i in 0 until count) {
            val start = b.getInt(0x40 + i * 0x40 + 12)
            val len = b.getInt(0x40 + i * 0x40 + 8)
            if (start < header || len < 0 || start.toLong() + len > size) return false
        }
        return true
    }

    fun crc(d: ByteArray, size: Int): Int = CRC32().also { it.update(d, 4, size - 4) }.value.toInt().inv()
}
