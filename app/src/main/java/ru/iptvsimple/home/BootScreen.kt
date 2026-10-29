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
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.roundToInt

/** Что показывать во время загрузки Android. */
enum class BootLogo(@StringRes val title: Int) {
    Stock(R.string.boot_stock),
    Homie(R.string.boot_homie),
    Custom(R.string.boot_custom),
}

/** Меняем только bootanimation.zip; Stock также возвращает проверенные копии от старых версий. */
object BootScreen {
    const val CUSTOM_PATH = "/sdcard/Homie/boot-logo.png"
    private const val BACKUP = "/data/local/homie-boot"
    private const val ODM_BOOTUP = "/mnt/vendor/odm_ext/logo_files/bootup.bmp"
    private const val BLOCK_W = 600
    private const val BLOCK_H = 360
    private const val LOGO_H = 220
    private const val BAR_W = 280
    private const val BAR_H = 6
    private const val BAR_Y = LOGO_H + 110
    private const val FPS = 30
    private val mutex = Mutex()

    sealed interface Result {
        data object Done : Result
        data class DoneWithWarning(val message: String) : Result
        data object NoRoot : Result
        data object NoCustomImage : Result
        data class Failed(val message: String) : Result
    }

    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    fun current(context: Context): BootLogo = prefs(context).getString("boot_logo", null)
        ?.let { name -> BootLogo.entries.firstOrNull { it.name == name } } ?: BootLogo.Stock

    /** Общая блокировка и отдельный staging на операцию; вызывать вне главного потока. */
    suspend fun apply(context: Context, choice: BootLogo): Result = mutex.withLock {
        runCatching {
            val root = Root.run("id", 60)
            if (root.timedOut) return@withLock result(context, root)
            if (!root.success || !Regex("\\buid=0\\b").containsMatchIn(root.output)) return@withLock Result.NoRoot
            val dir = Files.createTempDirectory(context.cacheDir.toPath(), "boot-").toFile()
            val result = try {
                if (choice == BootLogo.Stock) {
                    val animation = Root.run(animationScript(null), 120)
                    result(context, if (animation.success) restoreLegacy(dir) else animation)
                } else install(context, choice, dir)
            } finally { dir.deleteRecursively() }
            if (result == Result.Done || result is Result.DoneWithWarning) {
                prefs(context).edit().putString("boot_logo", choice.name).apply()
            }
            result
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun install(context: Context, choice: BootLogo, dir: File): Result {
        val custom = File(dir, "custom.png")
        val prep = Root.run(script("""
            if [ -f $CUSTOM_PATH ]; then step cp $CUSTOM_PATH '${custom.path}'; chmod 644 '${custom.path}'; fi
        """), 120)
        if (!prep.success) return result(context, prep)
        val art = if (choice == BootLogo.Custom) customArt(custom.path) ?: return Result.NoCustomImage else homieWordmark(context)
        val zip = File(dir, "bootanimation.zip")
        try { zip.writeBytes(bootAnimation(art)) } finally { art.recycle() }
        return result(context, Root.run(animationScript(zip.path), 120))
    }

    /** null — восстановление анимации; этот скрипт никогда не обращается к logo/ODM. */
    internal fun animationScript(source: String?): String = script("""
        animation_state ${if (source != null) "install" else "restore"}
        if [ -n "${'$'}P" ]; then
          rw "${'$'}P"
          if [ '${source != null}' = true ] || [ "${'$'}ORIGINAL" = present ]; then
            step mkdir -p "${'$'}P/media"
            step chmod 755 "${'$'}P/media"
            replace_file '${source ?: "$BACKUP/bootanimation.zip"}' "${'$'}P/media/bootanimation.zip"
          else
            step rm -f "${'$'}P/media/bootanimation.zip"
            step sync
          fi
        fi
    """)

    /** Единственный оставшийся путь записи logo — возврат проверенной старой копии. */
    internal fun restoreLegacy(dir: File, bmpBounds: (File) -> Boolean = ::bmpBoundsValid,
        run: (String) -> Root.Output = { Root.run(it, 120) }): Root.Output {
        val logo = File(dir, "logo.old")
        val path = logo.invariantSeparatorsPath
        val odm = File(dir, "odm.old")
        val odmPath = odm.invariantSeparatorsPath
        val staged = run(script("""
            if [ -e $BACKUP/logo.img ]; then
              if command -v blockdev >/dev/null && command -v stat >/dev/null &&
                SIZE=${'$'}(blockdev --getsize64 /dev/block/logo) && [ "${'$'}SIZE" -gt 0 ] &&
                [ "${'$'}(stat -c %s $BACKUP/logo.img)" = "${'$'}SIZE" ] &&
                step cp $BACKUP/logo.img '$path' && chmod 644 '$path'; then :
              else rm -f '$path'; echo skipped:logo; fi
            fi
            if [ -e $BACKUP/odm_bootup.bmp ]; then
              if step cp $BACKUP/odm_bootup.bmp '$odmPath' && chmod 644 '$odmPath' && (
                if [ -e $BACKUP/odm_bootup.bmp.sha256 ]; then
                  EXPECTED=${'$'}(cat $BACKUP/odm_bootup.bmp.sha256) && HASH=${'$'}(sha256sum '$odmPath') &&
                    [ "${'$'}{#EXPECTED}" = 64 ] && [ "${'$'}{HASH%% *}" = "${'$'}EXPECTED" ]
                fi
              ); then :
              else rm -f '$odmPath'; echo skipped:odm; fi
            fi
        """))
        if (!staged.success) return staged
        val valid = logo.exists() && AmlRes.isValid(logo.readBytes())
        val validOdm = odm.exists() && runCatching { BootImage.validBmpHeader(odm.readBytes()) && bmpBounds(odm) }.getOrDefault(false)
        val restored = run(script("""
            ${if (valid) """
            if command -v dd >/dev/null && SIZE=${'$'}(blockdev --getsize64 /dev/block/logo) &&
              [ "${'$'}(stat -c %s '$path')" = "${'$'}SIZE" ]; then
              step dd if='$path' of=/dev/block/logo bs=4096
              step sync
              step cmp -s '$path' /dev/block/logo
              step rm -f $BACKUP/logo.img $BACKUP/logo.img.sha256
            else echo skipped:logo; fi
            """ else ""}
            ${if (validOdm) """
            rw /mnt/vendor/odm_ext
            replace_file '$odmPath' $ODM_BOOTUP
            step rm -f $BACKUP/odm_bootup.bmp $BACKUP/odm_bootup.bmp.sha256
            """ else ""}
        """))
        val skipped = (if (logo.exists() && !valid) "\nskipped:logo" else "") +
            (if (odm.exists() && !validOdm) "\nskipped:odm" else "")
        return restored.copy(output = staged.output + skipped + "\n" + restored.output)
    }

    private fun bmpBoundsValid(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    /** Один таймер: 75 с на работу, до внешних 120 с остаётся время на remount,ro. */
    internal fun script(body: String): String = """
        set -e
        for TOOL in sha256sum cmp mount cp mv rm mkdir chmod cat grep sync sleep; do
          command -v "${'$'}TOOL" >/dev/null 2>&1 || { echo "tool ${'$'}TOOL missing"; exit 64; }
        done
        RW=''; CHILD=''; TMP=''; P=''
        cleanup() {
          STATUS=${'$'}?
          trap - EXIT
          trap '' HUP INT TERM
          set +e
          kill "${'$'}WATCHDOG" 2>/dev/null
          if [ -n "${'$'}TMP" ]; then step rm -f "${'$'}TMP"; fi
          for TARGET in ${'$'}RW; do
            if ! step mount -o remount,ro "${'$'}TARGET"; then echo "remount-ro:${'$'}TARGET"; STATUS=74; fi
          done
          exit "${'$'}STATUS"
        }
        stop() {
          trap '' HUP INT TERM
          if [ -n "${'$'}CHILD" ]; then kill -KILL "${'$'}CHILD" 2>/dev/null || :; wait "${'$'}CHILD" 2>/dev/null || :; fi
          exit "${'$'}1"
        }
        trap cleanup EXIT
        trap 'stop 124' TERM
        trap 'stop 130' HUP INT
        MAIN=${'$'}${'$'}
        (sleep 75; kill -TERM "${'$'}MAIN") >/dev/null 2>&1 &
        WATCHDOG=${'$'}!
        step() { "${'$'}@" & CHILD=${'$'}!; R=0; wait "${'$'}CHILD" || R=${'$'}?; CHILD=''; return "${'$'}R"; }
        fail() { echo "${'$'}2" >&2; exit "${'$'}1"; }
        rw() { RW="${'$'}1 ${'$'}RW"; step mount -o remount,rw "${'$'}1"; }
        replace_file() {
          TMP="${'$'}2.tmp"
          step cp "${'$'}1" "${'$'}TMP" && step cmp -s "${'$'}1" "${'$'}TMP" && step chmod 644 "${'$'}TMP" &&
            step sync && step mv "${'$'}TMP" "${'$'}2" && step sync
        }
        verify_animation() {
          [ -f $BACKUP/bootanimation.zip ] && [ -f $BACKUP/bootanimation.zip.sha256 ] || fail 72 'animation backup or checksum missing'
          EXPECTED=${'$'}(cat $BACKUP/bootanimation.zip.sha256) || fail 72 'animation checksum unreadable'
          HASH=${'$'}(sha256sum $BACKUP/bootanimation.zip) || fail 72 'animation checksum failed'
          [ "${'$'}{#EXPECTED}" = 64 ] && [ "${'$'}{HASH%% *}" = "${'$'}EXPECTED" ] || fail 72 'animation backup checksum mismatch'
        }
        animation_state() {
          if [ -e $BACKUP/animation.state ]; then
            read P ORIGINAL EXTRA < $BACKUP/animation.state || fail 66 'animation state unreadable'
            [ -z "${'$'}EXTRA" ] || fail 66 'animation state invalid'
          else
            if [ -e $BACKUP/anim_path ]; then P=${'$'}(cat $BACKUP/anim_path) || fail 66 'old animation path unreadable'; ORIGINAL=absent
            elif [ "${'$'}1" = install ]; then
              if grep -q ' /oem ' /proc/mounts; then P=/oem
              elif grep -q ' /product ' /proc/mounts; then P=/product
              else fail 66 'animation mount missing'; fi
              # Без state первая копия не завершена; media ещё не меняли, сохраняем её заново.
              step rm -f $BACKUP/bootanimation.zip $BACKUP/bootanimation.zip.tmp $BACKUP/bootanimation.zip.sha256 $BACKUP/bootanimation.zip.sha256.tmp
              ORIGINAL=absent
              if [ -e "${'$'}P/media/bootanimation.zip" ]; then
                step mkdir -p $BACKUP
                step chmod 700 $BACKUP
                step cp "${'$'}P/media/bootanimation.zip" $BACKUP/bootanimation.zip.tmp
                step cmp -s "${'$'}P/media/bootanimation.zip" $BACKUP/bootanimation.zip.tmp
                HASH=${'$'}(sha256sum $BACKUP/bootanimation.zip.tmp) || fail 72 'animation checksum failed'
                echo "${'$'}{HASH%% *}" > $BACKUP/bootanimation.zip.sha256.tmp
                step sync
                step mv $BACKUP/bootanimation.zip.sha256.tmp $BACKUP/bootanimation.zip.sha256
                step sync
                step mv $BACKUP/bootanimation.zip.tmp $BACKUP/bootanimation.zip
                ORIGINAL=present
              fi
            else return 0; fi
            case "${'$'}P" in /oem|/product) ;; *) fail 66 'animation path invalid';; esac
            step mkdir -p $BACKUP
            step chmod 700 $BACKUP
            echo "${'$'}P ${'$'}ORIGINAL" > $BACKUP/animation.state.tmp
            step sync
            step mv $BACKUP/animation.state.tmp $BACKUP/animation.state
            step sync
          fi
          case "${'$'}P" in /oem|/product) ;; *) fail 66 'animation path invalid';; esac
          case "${'$'}ORIGINAL" in present) verify_animation;; absent) ;; *) fail 66 'animation state invalid';; esac
        }
        ${body.trimIndent()}
    """.trimIndent()

    private fun result(context: Context, out: Root.Output): Result {
        if (!out.success) return Result.Failed(if (out.timedOut) context.getString(R.string.boot_timeout)
            else context.getString(R.string.boot_command_failed, out.exitCode?.toString() ?: "?", out.output.trim().takeLast(200)))
        val warnings = listOf("logo" to R.string.boot_old_logo_skipped, "odm" to R.string.boot_old_odm_skipped)
            .filter { "skipped:${it.first}" in out.output.lineSequence() }.map { context.getString(it.second) }
        return if (warnings.isEmpty()) Result.Done else Result.DoneWithWarning(warnings.joinToString(" "))
    }

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

    private fun frame(art: Bitmap, barAlpha: Float, progress: Float): Bitmap {
        val bmp = Bitmap.createBitmap(BLOCK_W, BLOCK_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
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
                last = png(frame(art, a, progress))
                put("part0/%03d.png".format(i), last)
            }
            put("part1/000.png", last)
        }
        return out.toByteArray()
    }

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it); b.recycle() }.toByteArray()

}

/** Размер декодирования — не больше удвоенного рисунка 420 × 220, с сохранением пропорций. */
internal object BootImage {
    /** Размер BMP в заголовке — беззнаковый uint32, little-endian. Декодирование проверяется отдельно. */
    fun validBmpHeader(data: ByteArray): Boolean {
        if (data.size < 14 || data[0] != 'B'.code.toByte() || data[1] != 'M'.code.toByte()) return false
        val size = (2..5).fold(0L) { value, i -> value or ((data[i].toLong() and 0xff) shl ((i - 2) * 8)) }
        return size == data.size.toLong()
    }

    fun sampleSize(width: Int, height: Int): Int? {
        if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 100_000_000) return null
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > 840 || (height.toLong() + sample - 1) / sample > 440) sample *= 2
        return sample
    }
}
