package ru.iptvsimple.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.StringRes
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Что показывать при включении приставки. */
enum class BootLogo(@StringRes val title: Int) {
    Stock(R.string.boot_stock),
    Homie(R.string.boot_homie),
    Custom(R.string.boot_custom),
}

/**
 * Заставка при включении — обе страницы загрузки (нужен root):
 * 1) картинка загрузчика: «bootup» в разделе logo (формат Amlogic AML_RES) и, если есть,
 *    logo_files/bootup.bmp в разделе odm_ext — загрузчик берёт её первой;
 * 2) анимация Android: bootanimation.zip в /oem/media или /product/media — система берёт её
 *    раньше, чем анимацию из /system, а сам /system не трогаем.
 * Перед первой записью оригиналы сохраняются в [BACKUP]; «Как в прошивке» возвращает их.
 * Своя картинка — /sdcard/Homie/boot-logo.png: светлый рисунок на прозрачном фоне.
 */
object BootScreen {
    const val CUSTOM_PATH = "/sdcard/Homie/boot-logo.png"
    private const val BACKUP = "/data/local/homie-boot"
    private const val ODM_BOOTUP = "/mnt/vendor/odm_ext/logo_files/bootup.bmp"

    private const val W = 1920
    private const val H = 1080
    private const val FPS = 30

    // Блок «рисунок + полоска», по центру экрана. Анимация рисуется только этим блоком.
    private const val BLOCK_W = 600
    private const val BLOCK_H = 360
    private const val LOGO_H = 220
    private const val BAR_W = 280
    private const val BAR_H = 6
    private const val BAR_Y = LOGO_H + 110

    sealed interface Result {
        data object Done : Result
        data object DoneAnimationOnly : Result
        data object NoRoot : Result
        data object NoCustomImage : Result
        data class Failed(val message: String) : Result
    }

    fun current(context: Context): BootLogo =
        prefs(context).getString(KEY, null)?.let { name -> BootLogo.entries.firstOrNull { it.name == name } } ?: BootLogo.Stock

    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private const val KEY = "boot_logo"

    /** Ставит выбранную заставку. Долго (секунды): вызывать не в главном потоке. */
    fun apply(context: Context, choice: BootLogo): Result = runCatching {
        if (!Root.available()) return Result.NoRoot
        val result = if (choice == BootLogo.Stock) restore() else install(context, choice)
        if (result == Result.Done || result == Result.DoneAnimationOnly) {
            prefs(context).edit().putString(KEY, choice.name).apply()
        }
        result
    }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }

    private fun install(context: Context, choice: BootLogo): Result {
        val dir = File(context.cacheDir, "boot").apply { deleteRecursively(); mkdirs() }
        val path = dir.absolutePath
        // Резервные копии (только один раз) и исходный раздел logo для пересборки.
        val prep = Root.run(
            """
            mkdir -p $BACKUP
            if [ -e /dev/block/logo ]; then
              [ -f $BACKUP/logo.img ] || dd if=/dev/block/logo of=$BACKUP/logo.img bs=4096
              cp $BACKUP/logo.img $path/logo.src && chmod 644 $path/logo.src
            fi
            if [ -f $ODM_BOOTUP ]; then [ -f $BACKUP/odm_bootup.bmp ] || cp $ODM_BOOTUP $BACKUP/odm_bootup.bmp; fi
            if [ -f $CUSTOM_PATH ]; then cp $CUSTOM_PATH $path/custom.png && chmod 644 $path/custom.png; fi
            echo ok
            """,
        )
        if (!prep.contains("ok")) return Result.Failed(prep.trim().takeLast(200))

        val art = when (choice) {
            BootLogo.Custom -> BitmapFactory.decodeFile("$path/custom.png") ?: return Result.NoCustomImage
            else -> homieWordmark(context)
        }
        val still = frame(art, barAlpha = 0f, progress = 0f, block = false)
        File(dir, "bootanimation.zip").writeBytes(bootAnimation(art))
        File(dir, "bootup.bmp").writeBytes(bmp565(still))
        val logoSrc = File(dir, "logo.src")
        val logoNew = logoSrc.takeIf { it.exists() }?.readBytes()?.let { AmlRes.replace(it, "bootup", gzipNamed("bootup", bmp565(still))) }
        logoNew?.let { File(dir, "logo.new").writeBytes(it) }

        val out = Root.run(
            """
            ${if (logoNew != null) "dd if=$path/logo.new of=/dev/block/logo bs=4096 && sync" else ""}
            if [ -f $ODM_BOOTUP ]; then
              mount -o remount,rw /mnt/vendor/odm_ext && cp $path/bootup.bmp $ODM_BOOTUP && sync && echo odm
              mount -o remount,ro /mnt/vendor/odm_ext
            fi
            P=${'$'}(cat $BACKUP/anim_path 2>/dev/null)
            if [ -z "${'$'}P" ]; then
              if grep -q " /oem " /proc/mounts; then P=/oem; elif grep -q " /product " /proc/mounts; then P=/product; fi
              echo "${'$'}P" > $BACKUP/anim_path
            fi
            [ -n "${'$'}P" ] || { echo noanim; exit 1; }
            mount -o remount,rw ${'$'}P
            mkdir -p ${'$'}P/media
            cp $path/bootanimation.zip ${'$'}P/media/bootanimation.zip
            chmod 755 ${'$'}P/media; chmod 644 ${'$'}P/media/bootanimation.zip
            sync
            mount -o remount,ro ${'$'}P
            echo ok
            """,
        )
        dir.deleteRecursively()
        if (!out.contains("ok")) return Result.Failed(out.trim().takeLast(200))
        return if (logoNew != null || out.contains("odm")) Result.Done else Result.DoneAnimationOnly
    }

    private fun restore(): Result {
        val out = Root.run(
            """
            [ -f $BACKUP/logo.img ] && dd if=$BACKUP/logo.img of=/dev/block/logo bs=4096 && sync
            if [ -f $BACKUP/odm_bootup.bmp ] && [ -f $ODM_BOOTUP ]; then
              mount -o remount,rw /mnt/vendor/odm_ext && cp $BACKUP/odm_bootup.bmp $ODM_BOOTUP && sync
              mount -o remount,ro /mnt/vendor/odm_ext
            fi
            P=${'$'}(cat $BACKUP/anim_path 2>/dev/null)
            if [ -n "${'$'}P" ] && [ -f ${'$'}P/media/bootanimation.zip ]; then
              mount -o remount,rw ${'$'}P && rm -f ${'$'}P/media/bootanimation.zip && sync
              mount -o remount,ro ${'$'}P
            fi
            echo ok
            """,
        )
        return if (out.contains("ok")) Result.Done else Result.Failed(out.trim().takeLast(200))
    }

    // ---------- Рисование ----------

    /** Надпись «Homie» шрифтом приложения — заставка по умолчанию. */
    private fun homieWordmark(context: Context): Bitmap {
        val base = runCatching { context.resources.getFont(R.font.inter) }.getOrDefault(Typeface.DEFAULT)
        val face = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 600, false) else Typeface.create(base, Typeface.BOLD)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = 168f
            color = Color.WHITE
            letterSpacing = -0.02f
        }
        val text = "Homie"
        val w = paint.measureText(text).roundToInt() + 8
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent).roundToInt()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawText(text, 4f, -fm.ascent, paint)
        }
    }

    /**
     * Кадр: рисунок по центру верхней части блока и полоска под ним.
     * [block] — только блок (для анимации), иначе весь экран (для загрузчика).
     */
    private fun frame(art: Bitmap, barAlpha: Float, progress: Float, block: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(if (block) BLOCK_W else W, if (block) BLOCK_H else H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        if (!block) c.translate(((W - BLOCK_W) / 2).toFloat(), ((H - BLOCK_H) / 2).toFloat())
        val k = min(LOGO_H.toFloat() / art.height, 420f / art.width)
        val aw = art.width * k
        val ah = art.height * k
        val dst = RectF((BLOCK_W - aw) / 2f, (LOGO_H - ah) / 2f, (BLOCK_W + aw) / 2f, (LOGO_H + ah) / 2f)
        c.drawBitmap(art, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (barAlpha > 0f) {
            val x0 = (BLOCK_W - BAR_W) / 2f
            val r = BAR_H / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val track = (58 * barAlpha).roundToInt()
            p.color = Color.rgb(track, track, track)
            c.drawRoundRect(RectF(x0, BAR_Y.toFloat(), x0 + BAR_W, (BAR_Y + BAR_H).toFloat()), r, r, p)
            val fw = BAR_W * progress
            if (fw >= BAR_H) {
                val v = (235 * barAlpha).roundToInt()
                p.color = Color.rgb(v, v, v)
                c.drawRoundRect(RectF(x0, BAR_Y.toFloat(), x0 + fw, (BAR_Y + BAR_H).toFloat()), r, r, p)
            }
        }
        return bmp
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    /** bootanimation.zip: полоска проявляется и за 7 с доходит до ~90 %, затем держится до конца загрузки. */
    private fun bootAnimation(art: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) {
                val e = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = CRC32().also { it.update(data) }.value
                }
                zip.putNextEntry(e); zip.write(data); zip.closeEntry()
            }
            put("desc.txt", "$BLOCK_W $BLOCK_H $FPS\np 1 0 part0\np 0 0 part1\n".toByteArray())
            val n = 7 * FPS
            var last = ByteArray(0)
            for (i in 0 until n) {
                val t = i.toFloat() / FPS
                val a = smooth((t - 0.2f) / 0.4f)
                val q = ((t - 0.4f) / 6.6f).coerceIn(0f, 1f)
                val progress = 0.9f * (1 - (1 - q) * (1 - q))
                last = png(frame(art, a, progress, block = true))
                put("part0/%03d.png".format(i), last)
            }
            put("part1/000.png", last)
        }
        return out.toByteArray()
    }

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it); b.recycle() }.toByteArray()

    /** 16-битный BMP (RGB565, BITFIELDS, снизу вверх) — такой понимает загрузчик Amlogic. */
    private fun bmp565(b: Bitmap): ByteArray {
        val w = b.width
        val h = b.height
        val row = w * 2
        val pad = (4 - row % 4) % 4
        val pixels = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val dataSize = (row + pad) * h + 2
        val buf = ByteBuffer.allocate(70 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(70 + dataSize).putShort(0).putShort(0).putInt(70)
        buf.putInt(56).putInt(w).putInt(h).putShort(1).putShort(16).putInt(3).putInt(dataSize)
            .putInt(3779).putInt(3779).putInt(0).putInt(0)
        buf.putInt(0xF800).putInt(0x07E0).putInt(0x001F).putInt(0)
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                buf.putShort((((r shr 3) shl 11) or ((g shr 2) shl 5) or (bl shr 3)).toShort())
            }
            repeat(pad) { buf.put(0) }
        }
        return buf.array()
    }

    /** gzip с именем файла внутри — как в прошивке. */
    private fun gzipNamed(name: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 8, 0, 0, 0, 0, 2, 0xff.toByte()))
        out.write(name.toByteArray()); out.write(0)
        val d = Deflater(9, true).apply { setInput(data); finish() }
        val chunk = ByteArray(64 * 1024)
        while (!d.finished()) { val n = d.deflate(chunk); out.write(chunk, 0, n) }
        d.end()
        val crc = CRC32().also { it.update(data) }.value
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt()).putInt(data.size).array())
        return out.toByteArray()
    }
}

/** Раздел logo Amlogic (AML_RES_IMG v2): заголовок, таблица картинок, контрольная сумма. */
private object AmlRes {
    /** Заменяет картинку [name]; null — формат не тот, раздел трогать нельзя. */
    fun replace(src: ByteArray, name: String, body: ByteArray): ByteArray? {
        val b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        if (String(src, 8, 8, Charsets.US_ASCII) != "AML_RES!" || b.getInt(4) != 2) return null
        val size = b.getInt(16)
        val count = b.getInt(20)
        val align = max(1, b.getInt(24))
        if (size <= 0 || size > src.size || count !in 1..64 || b.getInt(0) != crc(src, size)) return null
        val header = 0x40 + count * 0x40
        val out = ByteArrayOutputStream().apply { write(src, 0, header) }
        val table = ByteBuffer.wrap(src.copyOf(header)).order(ByteOrder.LITTLE_ENDIAN)
        var found = false
        for (i in 0 until count) {
            val o = 0x40 + i * 0x40
            val itemName = String(src, o + 0x20, 0x20, Charsets.US_ASCII).substringBefore('\u0000')
            val start = b.getInt(o + 12)
            val len = b.getInt(o + 8)
            val data = if (itemName == name) body.also { found = true } else src.copyOfRange(start, start + len)
            val pos = (out.size() + align - 1) / align * align
            repeat(pos - out.size()) { out.write(0) }
            table.putInt(o + 8, data.size)
            table.putInt(o + 12, pos)
            out.write(data)
        }
        if (!found || out.size() > src.size) return null
        val result = out.toByteArray()
        System.arraycopy(table.array(), 0, result, 0, header)
        val rb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        rb.putInt(16, result.size)
        rb.putInt(0, crc(result, result.size))
        return result + ByteArray(src.size - result.size)
    }

    private fun crc(d: ByteArray, size: Int): Int = CRC32().also { it.update(d, 4, size - 4) }.value.toInt().inv()
}

/** Команды от root через su. Скрипт передаётся на вход оболочки. */
object Root {
    fun available(): Boolean = runCatching { run("id").contains("uid=0") }.getOrDefault(false)

    fun run(script: String): String {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script.trimIndent()); it.write("\nexit\n") }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }
}
