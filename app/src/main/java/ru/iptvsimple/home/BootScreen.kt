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
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread
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

    private val mutex = Mutex()

    /** Ставит заставку под общей для процесса блокировкой; вызывать вне главного потока. */
    suspend fun apply(context: Context, choice: BootLogo): Result = mutex.withLock {
        runCatching {
            val root = Root.run("id", 10)
            if (root.timedOut) return@withLock failure(context, root)
            if (!root.success || !Regex("\\buid=0\\b").containsMatchIn(root.output)) return@withLock Result.NoRoot
            val result = if (choice == BootLogo.Stock) restore(context) else install(context, choice)
            if (result == Result.Done || result == Result.DoneAnimationOnly) {
                prefs(context).edit().putString(KEY, choice.name).apply()
            }
            result
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun install(context: Context, choice: BootLogo): Result {
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "boot-").toFile()
        val path = dir.absolutePath
        try {
            // Резервные копии проверяются до любой записи в прошивку.
            val prep = Root.run(script("""
                mkdir -p $BACKUP
                chmod 700 $BACKUP
                if [ -e /dev/block/logo ] || [ -e $BACKUP/logo.img ]; then
                  if [ ! -e $BACKUP/logo.img ] && [ ! -e $BACKUP/logo.img.sha256 ]; then
                    partition_size
                    step dd if=/dev/block/logo of=$BACKUP/logo.img.tmp bs=4096
                    [ "${'$'}(stat -c %s $BACKUP/logo.img.tmp)" = "${'$'}SIZE" ] || exit 65
                    digest $BACKUP/logo.img.tmp > $BACKUP/logo.img.sha256.tmp
                    step sync
                    mv $BACKUP/logo.img.tmp $BACKUP/logo.img
                    mv $BACKUP/logo.img.sha256.tmp $BACKUP/logo.img.sha256
                    step sync
                  fi
                  verify_logo $BACKUP/logo.img
                  step cp $BACKUP/logo.img '$path/logo.src'
                  chmod 644 '$path/logo.src'
                  verify_logo '$path/logo.src'
                fi
                if [ -f $ODM_BOOTUP ] && [ ! -e $BACKUP/odm_bootup.bmp ]; then
                  save_file $ODM_BOOTUP $BACKUP/odm_bootup.bmp
                fi
                if [ ! -f $BACKUP/animation.state ]; then
                  [ ! -e $BACKUP/anim_path ] || exit 66
                  if grep -q ' /oem ' /proc/mounts; then P=/oem
                  elif grep -q ' /product ' /proc/mounts; then P=/product
                  else echo 'animation mount missing'; exit 1; fi
                  ORIGINAL=absent
                  if [ -e "${'$'}P/media/bootanimation.zip" ]; then
                    save_file "${'$'}P/media/bootanimation.zip" $BACKUP/bootanimation.zip
                    ORIGINAL=present
                  fi
                  echo "${'$'}P ${'$'}ORIGINAL" > $BACKUP/animation.state.tmp
                  mv $BACKUP/animation.state.tmp $BACKUP/animation.state
                  step sync
                fi
                animation_state
                if [ -f $CUSTOM_PATH ]; then
                  step cp $CUSTOM_PATH '$path/custom.png'
                  chmod 644 '$path/custom.png'
                fi
            """))
            if (!prep.success) return failure(context, prep)

            val art = when (choice) {
                BootLogo.Custom -> customArt("$path/custom.png") ?: return Result.NoCustomImage
                else -> homieWordmark(context)
            }
            val logoNew = try {
                val still = frame(art, barAlpha = 0f, progress = 0f, block = false)
                val bmp = try { bmp565(still) } finally { still.recycle() }
                File(dir, "bootanimation.zip").writeBytes(bootAnimation(art))
                File(dir, "bootup.bmp").writeBytes(bmp)
                File(dir, "logo.src").takeIf { it.exists() }?.readBytes()?.let {
                    AmlRes.replace(it, "bootup", gzipNamed("bootup", bmp))
                }
            } finally { art.recycle() }
            logoNew?.let { File(dir, "logo.new").writeBytes(it) }

            val out = Root.run(script("""
                animation_state
                ${if (logoNew != null) """
                verify_logo $BACKUP/logo.img
                partition_size
                [ "${'$'}(stat -c %s '$path/logo.new')" = "${'$'}SIZE" ] || exit 65
                echo writing:logo
                step dd if='$path/logo.new' of=/dev/block/logo bs=4096
                step sync
                step cmp -s '$path/logo.new' /dev/block/logo
                echo written:logo
                """ else ""}
                if [ -f $ODM_BOOTUP ]; then
                  echo writing:odm
                  rw /mnt/vendor/odm_ext
                  step cp '$path/bootup.bmp' $ODM_BOOTUP
                  step sync
                  step cmp -s '$path/bootup.bmp' $ODM_BOOTUP
                  echo written:odm
                fi
                echo writing:animation
                rw "${'$'}P"
                mkdir -p "${'$'}P/media"
                step cp '$path/bootanimation.zip' "${'$'}P/media/bootanimation.zip"
                chmod 755 "${'$'}P/media"
                chmod 644 "${'$'}P/media/bootanimation.zip"
                step sync
                step cmp -s '$path/bootanimation.zip' "${'$'}P/media/bootanimation.zip"
            """))
            if (!out.success) return failure(context, out)
            return if (logoNew != null || "written:odm" in out.output.lineSequence()) Result.Done else Result.DoneAnimationOnly
        } finally { dir.deleteRecursively() }
    }

    private fun restore(context: Context): Result {
        val out = Root.run(script("""
            # Сначала проверяем все сохранённые данные, затем меняем разделы.
            if [ -e $BACKUP/logo.img ] || [ -e $BACKUP/logo.img.sha256 ]; then verify_logo $BACKUP/logo.img; fi
            if [ -e $BACKUP/animation.state ]; then animation_state
            elif [ -e $BACKUP/anim_path ]; then exit 66; fi
            if [ -f $BACKUP/logo.img ]; then
              echo writing:logo
              step dd if=$BACKUP/logo.img of=/dev/block/logo bs=4096
              step sync
              step cmp -s $BACKUP/logo.img /dev/block/logo
            fi
            if [ -f $BACKUP/odm_bootup.bmp ]; then
              echo writing:odm
              rw /mnt/vendor/odm_ext
              step cp -p $BACKUP/odm_bootup.bmp $ODM_BOOTUP
              step sync
              step cmp -s $BACKUP/odm_bootup.bmp $ODM_BOOTUP
            fi
            if [ -f $BACKUP/animation.state ]; then
              echo writing:animation
              rw "${'$'}P"
              if [ "${'$'}ORIGINAL" = present ]; then
                mkdir -p "${'$'}P/media"
                step cp -p $BACKUP/bootanimation.zip "${'$'}P/media/bootanimation.zip"
                step cmp -s $BACKUP/bootanimation.zip "${'$'}P/media/bootanimation.zip"
              else
                rm -f "${'$'}P/media/bootanimation.zip"
              fi
              step sync
            fi
        """))
        return if (out.success) Result.Done else failure(context, out)
    }

    /** EXIT срабатывает и при ошибке; внутренний таймер оставляет время на remount,ro. */
    private fun script(body: String): String = """
        set -e
        RW=''
        WATCHDOG=''
        CHILD=''
        cleanup() {
          STATUS=${'$'}?
          trap - EXIT HUP INT TERM
          set +e
          if [ -n "${'$'}WATCHDOG" ]; then kill "${'$'}WATCHDOG" 2>/dev/null; fi
          for TARGET in ${'$'}RW; do
            if ! mount -o remount,ro "${'$'}TARGET"; then echo "remount-ro:${'$'}TARGET"; STATUS=74; fi
          done
          exit "${'$'}STATUS"
        }
        trap cleanup EXIT
        stop() {
          trap '' HUP INT TERM
          if [ -n "${'$'}CHILD" ]; then
            kill -KILL "${'$'}CHILD" 2>/dev/null || :
            wait "${'$'}CHILD" 2>/dev/null || :
          fi
          exit "${'$'}1"
        }
        trap 'stop 124' TERM
        trap 'stop 130' HUP INT
        MAIN=${'$'}${'$'}
        (sleep 50; kill -TERM "${'$'}MAIN") >/dev/null 2>&1 &
        WATCHDOG=${'$'}!
        # wait прерывается сигналом сразу; зависший dd/cp не переживёт таймаут оболочки.
        step() { "${'$'}@" & CHILD=${'$'}!; wait "${'$'}CHILD"; CHILD=''; }
        rw() { RW="${'$'}1 ${'$'}RW"; step mount -o remount,rw "${'$'}1"; }
        partition_size() {
          SIZE=${'$'}(blockdev --getsize64 /dev/block/logo) || exit 65
          case "${'$'}SIZE" in ''|*[!0-9]*) exit 65;; esac
          [ "${'$'}SIZE" -gt 0 ] || exit 65
        }
        digest() {
          HASH=${'$'}(sha256sum "${'$'}1") || exit 65
          echo "${'$'}{HASH%% *}"
        }
        verify_logo() {
          partition_size
          [ -f "${'$'}1" ] && [ -f $BACKUP/logo.img.sha256 ] || exit 65
          [ "${'$'}(stat -c %s "${'$'}1")" = "${'$'}SIZE" ] || exit 65
          EXPECTED=${'$'}(cat $BACKUP/logo.img.sha256)
          [ -n "${'$'}EXPECTED" ] && [ "${'$'}(digest "${'$'}1")" = "${'$'}EXPECTED" ] || exit 65
        }
        save_file() {
          step cp -p "${'$'}1" "${'$'}2.tmp"
          step cmp -s "${'$'}1" "${'$'}2.tmp"
          step sync
          mv "${'$'}2.tmp" "${'$'}2"
        }
        animation_state() {
          [ -f $BACKUP/animation.state ] || exit 66
          read P ORIGINAL EXTRA < $BACKUP/animation.state || exit 66
          case "${'$'}P" in /oem|/product) ;; *) exit 66;; esac
          [ -z "${'$'}EXTRA" ] || exit 66
          case "${'$'}ORIGINAL" in
            present) [ -f $BACKUP/bootanimation.zip ] || exit 66;;
            absent) ;;
            *) exit 66;;
          esac
        }
        ${body.trimIndent()}
    """.trimIndent()

    private fun failure(context: Context, out: Root.Output): Result.Failed {
        val message = when {
            out.timedOut -> context.getString(R.string.boot_timeout)
            out.exitCode == 65 -> context.getString(R.string.boot_backup_invalid)
            out.exitCode == 66 -> context.getString(R.string.boot_animation_backup_unknown)
            else -> context.getString(R.string.boot_command_failed, out.exitCode?.toString() ?: "?", out.output.trim().takeLast(200))
        }
        return Result.Failed(if (out.output.lineSequence().any { it.startsWith("writing:") }) {
            context.getString(R.string.boot_partial, message)
        } else message)
    }

    /** Сначала читаем размеры PNG: даже огромный исходник не должен исчерпать память. */
    private fun customArt(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = BootImage.sampleSize(options.outWidth, options.outHeight) ?: return null
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
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
internal object AmlRes {
    /** Заменяет картинку [name]; null — формат не тот, раздел трогать нельзя. */
    fun replace(src: ByteArray, name: String, body: ByteArray): ByteArray? {
        if (src.size < 0x40) return null
        val b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        if (String(src, 8, 8, Charsets.US_ASCII) != "AML_RES!" || b.getInt(4) != 2) return null
        val size = b.getInt(16)
        val count = b.getInt(20)
        val align = b.getInt(24)
        if (size !in 0x40..src.size || count !in 1..64 || align <= 0 || b.getInt(0) != crc(src, size)) return null
        val header = 0x40 + count * 0x40
        if (header > size) return null
        val out = ByteArrayOutputStream().apply { write(src, 0, header) }
        val table = ByteBuffer.wrap(src.copyOf(header)).order(ByteOrder.LITTLE_ENDIAN)
        var found = false
        for (i in 0 until count) {
            val o = 0x40 + i * 0x40
            val itemName = String(src, o + 0x20, 0x20, Charsets.US_ASCII).substringBefore('\u0000')
            val start = b.getInt(o + 12)
            val len = b.getInt(o + 8)
            if (start < header || len < 0 || start.toLong() + len > size) return null
            val data = if (itemName == name) body.also { found = true } else src.copyOfRange(start, start + len)
            val pos = (out.size().toLong() + align - 1) / align * align
            if (pos + data.size > src.size) return null
            repeat(pos.toInt() - out.size()) { out.write(0) }
            table.putInt(o + 8, data.size)
            table.putInt(o + 12, pos.toInt())
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

    fun crc(d: ByteArray, size: Int): Int = CRC32().also { it.update(d, 4, size - 4) }.value.toInt().inv()
}

/** Размер декодирования — не больше удвоенного рисунка 420 × 220, с сохранением пропорций. */
internal object BootImage {
    fun sampleSize(width: Int, height: Int): Int? {
        if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 100_000_000) return null
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > 840 || (height.toLong() + sample - 1) / sample > 440) sample *= 2
        return sample
    }
}

/** Команды от root через su; ввод и вывод читаются параллельно, ожидание ограничено. */
object Root {
    data class Output(val exitCode: Int?, val output: String, val timedOut: Boolean = false, val inputFailed: Boolean = false) {
        val success: Boolean get() = exitCode == 0 && !timedOut && !inputFailed
    }

    fun available(): Boolean = run("id", 10).let { it.success && Regex("\\buid=0\\b").containsMatchIn(it.output) }

    fun run(script: String, timeoutSeconds: Long = 60): Output {
        require(timeoutSeconds > 0)
        val process = try { ProcessBuilder("su").redirectErrorStream(true).start() }
        catch (e: java.io.IOException) { return Output(null, e.message.orEmpty()) }
        return execute(process, script, TimeUnit.SECONDS.toMillis(timeoutSeconds))
    }

    internal fun execute(process: Process, script: String, timeoutMillis: Long): Output {
        val output = StringBuilder()
        val ioFailure = AtomicReference<Exception?>()
        val reader = thread(isDaemon = true, name = "root-output") {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(output) {
                            output.append(buffer, 0, n)
                            if (output.length > 8192) output.delete(0, output.length - 8192)
                        }
                    }
                }
            }
        }
        val writer = thread(isDaemon = true, name = "root-input") {
            try { process.outputStream.bufferedWriter().use { it.write(script.trimIndent()); it.write("\n") } }
            catch (e: Exception) { ioFailure.set(e) }
        }
        var finished = false
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            if (!finished) {
                process.destroy()
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1500, TimeUnit.MILLISECONDS)
                }
            }
        }
        writer.join(200)
        reader.join(200)
        val code = if (finished) process.exitValue() else null
        val error = ioFailure.get()
        val text = synchronized(output) { output.toString() } + (error?.let { "\n${it.message}" } ?: "")
        return Output(code, text, !finished || code == 124, error != null)
    }
}
