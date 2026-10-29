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
            val root = Root.run("id", 60)
            if (root.timedOut) return@withLock failure(context, root)
            if (!root.success || !Regex("\\buid=0\\b").containsMatchIn(root.output)) return@withLock Result.NoRoot
            val dir = Files.createTempDirectory(context.cacheDir.toPath(), "boot-").toFile()
            val result = try {
                val migration = migrateLegacy(dir)
                if (!migration.success) return@withLock failure(context, migration)
                if (choice == BootLogo.Stock) restore(context) else install(context, choice, dir)
            } finally { dir.deleteRecursively() }
            if (result == Result.Done || result == Result.DoneAnimationOnly) {
                prefs(context).edit().putString(KEY, choice.name).apply()
            }
            result
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    internal fun migrateLegacy(dir: File, run: (String) -> Root.Output = { Root.run(it, 120) }): Root.Output {
        val staged = File(dir, "logo.legacy")
        val path = staged.invariantSeparatorsPath
        val out = run(script("check_firmware\nstage_legacy_logo '$path'"))
        if (!out.success) return out
        if (staged.exists() && !AmlRes.isValid(staged.readBytes())) {
            return Root.Output(67, "old logo backup cannot be trusted: AML_RES structure invalid")
        }
        return run(script("""
            check_firmware
            if [ -f '$path' ]; then
              step cmp -s '$path' $BACKUP/logo.img || fail 67 'old logo backup changed during validation'
              bind_firmware legacy
              hash_file '$path' $BACKUP/logo.img
            fi
            migrate_odm
            migrate_animation
        """))
    }

    private fun install(context: Context, choice: BootLogo, dir: File): Result {
        val path = dir.absolutePath
        // Резервные копии проверяются до любой записи в прошивку.
        val prep = Root.run(script("""
            mkdir -p $BACKUP
            chmod 700 $BACKUP
            check_firmware
            verify_backups
            bind_firmware
            if [ -e /dev/block/logo ] || [ -e $BACKUP/logo.img ]; then
              if [ ! -e $BACKUP/logo.img ] && [ ! -e $BACKUP/logo.img.sha256 ]; then
                partition_size
                step dd if=/dev/block/logo of=$BACKUP/logo.img.tmp bs=4096
                [ "${'$'}(stat -c %s $BACKUP/logo.img.tmp)" = "${'$'}SIZE" ] || fail 65 'logo backup size mismatch'
                aml_header $BACKUP/logo.img.tmp || fail 65 'logo backup AML_RES header invalid'
                publish_file $BACKUP/logo.img
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
            verify_backups
            if [ -f $CUSTOM_PATH ]; then
              step cp $CUSTOM_PATH '$path/custom.png'
              chmod 644 '$path/custom.png'
            fi
        """), 120)
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
        logoNew?.let {
            if (!AmlRes.isValid(it)) return failure(context, Root.Output(69, "new logo AML_RES structure invalid"))
            File(dir, "logo.new").writeBytes(it)
        }

        val out = Root.run(script("""
            check_firmware
            [ -f $BACKUP/firmware ] || fail 68 'firmware binding missing'
            verify_backups
            animation_state
            if [ -f $ODM_BOOTUP ]; then verify_file $BACKUP/odm_bootup.bmp; fi
            ${if (logoNew != null) """
            verify_logo $BACKUP/logo.img
            partition_size
            [ "${'$'}(stat -c %s '$path/logo.new')" = "${'$'}SIZE" ] || fail 69 'new logo size mismatch'
            aml_header '$path/logo.new' || fail 69 'new logo AML_RES header invalid'
            ROLLBACK=1
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
        """), 120)
        if (!out.success) return failure(context, out)
        return if (logoNew != null || "written:odm" in out.output.lineSequence()) Result.Done else Result.DoneAnimationOnly
    }

    private fun restore(context: Context): Result {
        val out = Root.run(script("""
            # Сначала проверяем все сохранённые данные, затем меняем разделы.
            check_firmware
            verify_backups
            if [ -e $BACKUP/logo.img ] || [ -e $BACKUP/odm_bootup.bmp ] || [ -e $BACKUP/animation.state ]; then bind_firmware; fi
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
        """), 120)
        return if (out.success) Result.Done else failure(context, out)
    }

    /** Основная работа — до 75 с, remount,ro и откат — ещё до 25 с; внешний лимит — 120 с. */
    internal fun script(body: String): String = """
        set -e
        for TOOL in blockdev sha256sum stat cmp dd mount getprop cp mv rm mkdir chmod cat grep sync sleep; do
          command -v "${'$'}TOOL" >/dev/null 2>&1 || { echo "tool ${'$'}TOOL missing"; exit 64; }
        done
        RW=''
        WATCHDOG=''
        CHILD=''
        ROLLBACK=0
        cleanup() {
          WRITE_STATUS=${'$'}?
          STATUS=${'$'}WRITE_STATUS
          trap - EXIT
          set +e
          if [ -n "${'$'}WATCHDOG" ]; then kill "${'$'}WATCHDOG" 2>/dev/null; fi
          trap 'if [ "${'$'}WRITE_STATUS" -ne 0 ] && [ "${'$'}ROLLBACK" = 1 ]; then echo "rollback failed:logo (timeout)"; stop 76; else echo "remount-ro:timeout"; stop 74; fi' HUP INT TERM
          (sleep 25; kill -TERM "${'$'}MAIN") >/dev/null 2>&1 &
          WATCHDOG=${'$'}!
          for TARGET in ${'$'}RW; do
            if ! step mount -o remount,ro "${'$'}TARGET"; then echo "remount-ro:${'$'}TARGET"; STATUS=74; fi
          done
          if [ "${'$'}WRITE_STATUS" -ne 0 ] && [ "${'$'}ROLLBACK" = 1 ]; then
            if (check_firmware; verify_logo $BACKUP/logo.img) &&
              step dd if=$BACKUP/logo.img of=/dev/block/logo bs=4096 &&
              step sync && step cmp -s $BACKUP/logo.img /dev/block/logo; then echo 'rolled back:logo'
            else echo 'rollback failed:logo'; STATUS=76; fi
          fi
          kill "${'$'}WATCHDOG" 2>/dev/null
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
        (sleep 75; kill -TERM "${'$'}MAIN") >/dev/null 2>&1 &
        WATCHDOG=${'$'}!
        # wait прерывается сигналом сразу; зависший dd/cp не переживёт таймаут оболочки.
        step() { "${'$'}@" & CHILD=${'$'}!; R=0; wait "${'$'}CHILD" || R=${'$'}?; CHILD=''; return "${'$'}R"; }
        rw() { RW="${'$'}1 ${'$'}RW"; step mount -o remount,rw "${'$'}1"; }
        fail() { echo "${'$'}2" >&2; exit "${'$'}1"; }
        partition_size() {
          SIZE=${'$'}(blockdev --getsize64 /dev/block/logo) || fail 70 'logo partition size unavailable'
          case "${'$'}SIZE" in ''|*[!0-9]*) fail 70 'logo partition size invalid';; esac
          [ "${'$'}SIZE" -gt 0 ] || fail 70 'logo partition size invalid'
        }
        digest() {
          HASH=${'$'}(sha256sum "${'$'}1") || fail 71 'backup checksum failed'
          echo "${'$'}{HASH%% *}"
        }
        aml_header() {
          MAGIC=${'$'}(dd if="${'$'}1" bs=1 skip=8 count=8 2>/dev/null) || return 1
          [ "${'$'}MAGIC" = 'AML_RES!' ] || return 1
          return 0
        }
        check_firmware() {
          FIRMWARE=${'$'}(getprop ro.build.fingerprint) || fail 68 'firmware fingerprint unavailable'
          [ -n "${'$'}FIRMWARE" ] || fail 68 'firmware fingerprint unavailable'
          if [ -e $BACKUP/firmware ]; then
            BOUND=${'$'}(cat $BACKUP/firmware) || fail 68 'firmware binding unreadable'
            [ "${'$'}BOUND" = legacy ] || [ "${'$'}BOUND" = "${'$'}FIRMWARE" ] || fail 68 'firmware changed, backups are from previous firmware'
          fi
        }
        bind_firmware() {
          if [ ! -e $BACKUP/firmware ]; then
            # У старых копий прошивка неизвестна: legacy не доказывает совместимость и не переименовывается.
            printf '%s\n' "${'$'}{1:-${'$'}FIRMWARE}" > $BACKUP/firmware.tmp || return ${'$'}?
            step sync && mv $BACKUP/firmware.tmp $BACKUP/firmware && step sync
          fi
        }
        stage_legacy_logo() {
          if [ -e $BACKUP/logo.img ] && [ ! -e $BACKUP/logo.img.sha256 ]; then
            [ ! -e $BACKUP/firmware ] || [ "${'$'}(cat $BACKUP/firmware)" = legacy ] || fail 65 'logo backup checksum missing'
            partition_size
            [ "${'$'}(stat -c %s $BACKUP/logo.img)" = "${'$'}SIZE" ] && aml_header $BACKUP/logo.img ||
              fail 67 'old logo backup cannot be trusted: size or AML_RES header invalid'
            step cp $BACKUP/logo.img "${'$'}1" && chmod 644 "${'$'}1"
          fi
        }
        verify_logo() {
          partition_size
          [ -f "${'$'}1" ] && [ -f $BACKUP/logo.img.sha256 ] || fail 65 'logo backup or checksum missing'
          [ "${'$'}(stat -c %s "${'$'}1")" = "${'$'}SIZE" ] || fail 65 'logo backup size mismatch'
          EXPECTED=${'$'}(cat $BACKUP/logo.img.sha256) || fail 65 'logo backup checksum unreadable'
          ACTUAL=${'$'}(digest "${'$'}1") || fail 65 'logo backup checksum failed'
          valid_hash && [ "${'$'}ACTUAL" = "${'$'}EXPECTED" ] || fail 65 'logo backup checksum mismatch'
        }
        hash_file() {
          DEST=${'$'}{2:-${'$'}1}
          digest "${'$'}1" > "${'$'}DEST.sha256.tmp" || return ${'$'}?
          step sync && mv "${'$'}DEST.sha256.tmp" "${'$'}DEST.sha256" && step sync
        }
        publish_file() {
          # Хеш публикуется первым: финальное имя без хеша бывает только у старых версий.
          hash_file "${'$'}1.tmp" "${'$'}1" && mv "${'$'}1.tmp" "${'$'}1" && step sync
        }
        save_file() {
          step cp -p "${'$'}1" "${'$'}2.tmp" && step cmp -s "${'$'}1" "${'$'}2.tmp" && publish_file "${'$'}2"
        }
        verify_file() {
          [ -f "${'$'}1" ] && [ -f "${'$'}1.sha256" ] || fail 72 "backup or checksum missing: ${'$'}1"
          EXPECTED=${'$'}(cat "${'$'}1.sha256") || fail 72 "backup checksum unreadable: ${'$'}1"
          ACTUAL=${'$'}(digest "${'$'}1") || fail 72 "backup checksum failed: ${'$'}1"
          valid_hash && [ "${'$'}ACTUAL" = "${'$'}EXPECTED" ] || fail 72 "backup checksum mismatch: ${'$'}1"
        }
        valid_hash() {
          [ "${'$'}{#EXPECTED}" = 64 ] || return 1
          case "${'$'}EXPECTED" in *[!0-9a-f]*) return 1;; esac
          return 0
        }
        migrate_odm() {
          if [ -e $BACKUP/odm_bootup.bmp ] && [ ! -e $BACKUP/odm_bootup.bmp.sha256 ]; then
            [ ! -e $BACKUP/firmware ] || [ "${'$'}(cat $BACKUP/firmware)" = legacy ] || fail 72 'odm backup checksum missing'
            MAGIC=${'$'}(dd if=$BACKUP/odm_bootup.bmp bs=1 count=2 2>/dev/null) || fail 67 'old odm backup unreadable'
            [ -s $BACKUP/odm_bootup.bmp ] && [ "${'$'}MAGIC" = BM ] || fail 67 'old odm backup cannot be trusted: BMP header invalid'
            bind_firmware legacy && hash_file $BACKUP/odm_bootup.bmp
          fi
        }
        migrate_animation() {
          if [ ! -e $BACKUP/animation.state ] && [ -e $BACKUP/anim_path ]; then
            P=${'$'}(cat $BACKUP/anim_path) || fail 73 'old animation path unreadable'
            case "${'$'}P" in /oem|/product) ;; *) fail 73 'old animation path invalid';; esac
            # Старый restore удалял анимацию, даже если до установки она существовала.
            bind_firmware legacy || return ${'$'}?
            echo "${'$'}P absent" > $BACKUP/animation.state.tmp || return ${'$'}?
            mv $BACKUP/animation.state.tmp $BACKUP/animation.state && step sync
          fi
        }
        verify_backups() {
          if [ -e $BACKUP/logo.img ] || [ -e $BACKUP/logo.img.sha256 ]; then verify_logo $BACKUP/logo.img; fi
          if [ -e $BACKUP/odm_bootup.bmp ] || [ -e $BACKUP/odm_bootup.bmp.sha256 ]; then verify_file $BACKUP/odm_bootup.bmp; fi
          if [ -e $BACKUP/bootanimation.zip ] || [ -e $BACKUP/bootanimation.zip.sha256 ]; then verify_file $BACKUP/bootanimation.zip; fi
          if [ -e $BACKUP/animation.state ]; then animation_state; fi
        }
        animation_state() {
          [ -f $BACKUP/animation.state ] || fail 66 'animation state missing'
          read P ORIGINAL EXTRA < $BACKUP/animation.state || fail 66 'animation state unreadable'
          case "${'$'}P" in /oem|/product) ;; *) fail 66 'animation state path invalid';; esac
          [ -z "${'$'}EXTRA" ] || fail 66 'animation state invalid'
          case "${'$'}ORIGINAL" in
            present) verify_file $BACKUP/bootanimation.zip;;
            absent) ;;
            *) fail 66 'animation state invalid';;
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
        if ("rolled back:logo" in out.output.lineSequence()) {
            return Result.Failed(context.getString(R.string.boot_rolled_back, message))
        }
        val rollback = out.output.lineSequence().lastOrNull { it.startsWith("rollback failed:logo") }
        val detail = if (rollback != null) "$message ($rollback)" else message
        return Result.Failed(if (out.output.lineSequence().any { it.startsWith("writing:") }) {
            context.getString(R.string.boot_partial, detail)
        } else detail)
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
    fun isValid(src: ByteArray): Boolean = parse(src) != null

    /** Одна проверка структуры и CRC для миграции, замены картинки и готового образа. */
    private fun parse(src: ByteArray): ByteBuffer? {
        if (src.size < 0x40) return null
        val b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        if (String(src, 8, 8, Charsets.US_ASCII) != "AML_RES!" || b.getInt(4) != 2) return null
        val size = b.getInt(16)
        val count = b.getInt(20)
        val align = b.getInt(24)
        if (size !in 0x40..src.size || count !in 1..64 || align !in 1..src.size || b.getInt(0) != crc(src, size)) return null
        val header = 0x40 + count * 0x40
        if (header > size) return null
        for (i in 0 until count) {
            val start = b.getInt(0x40 + i * 0x40 + 12)
            val len = b.getInt(0x40 + i * 0x40 + 8)
            if (start < header || len < 0 || start.toLong() + len > size) return null
        }
        return b
    }

    /** Заменяет картинку [name]; null — формат не тот, раздел трогать нельзя. */
    fun replace(src: ByteArray, name: String, body: ByteArray): ByteArray? {
        val b = parse(src) ?: return null
        val count = b.getInt(20)
        val align = b.getInt(24)
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

    fun available(): Boolean = run("id", 60).let { it.success && Regex("\\buid=0\\b").containsMatchIn(it.output) }

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
