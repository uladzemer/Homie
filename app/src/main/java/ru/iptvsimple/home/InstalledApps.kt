package ru.iptvsimple.home

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Установленное приложение. [banner] — широкая картинка 16:9 для телевизора; у приложений
 * без баннера рисуем значок с названием.
 */
class InstalledApp(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
)

object InstalledApps {
    private var cached: Map<String, InstalledApp>? = null
    private var locale = ""
    private val generation = AtomicInteger()
    @Volatile
    private var loadedGeneration = -1
    val invalidated: Boolean get() = loadedGeneration != generation.get()
    private var observing = false
    private data class ImageKey(val pkg: String, val updated: Long)
    private val images = HashMap<ImageKey, Pair<ImageBitmap?, ImageBitmap?>>()

    fun invalidate() { generation.incrementAndGet() }

    /** Все приложения, которые можно запустить. Долго (десятки картинок) — вызывать не с главного потока. */
    @Synchronized
    fun load(context: Context): Map<String, InstalledApp> {
        if (!observing) {
            // Приёмник живёт вместе с кэшем, даже если Activity была уничтожена.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { invalidate() }
            }
            context.applicationContext.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            })
            context.applicationContext.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
            })
            observing = true
        }
        val loadingGeneration = generation.get()
        val currentLocale = context.resources.configuration.locales.toLanguageTags()
        if (loadedGeneration == loadingGeneration && locale == currentLocale) cached?.let { return it }
        if (locale != currentLocale) images.clear()
        val used = HashSet<ImageKey>()
        val pm = context.packageManager
        val result = LinkedHashMap<String, InstalledApp>()
        TvInputs.load(context).forEach { result[it.pkg] = it }
        fun collect(category: String) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in KnownApps.neverShown || pkg in result) continue
                val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull() ?: continue
                val key = ImageKey(pkg, updated)
                used += key
                val image = images.getOrPut(key) {
                    val banner = runCatching { activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm) }
                        .getOrNull()?.toImage(320, 180)
                    val icon = if (banner == null) runCatching { activity.loadIcon(pm).toImage(160, 160) }.getOrNull() else null
                    banner to icon
                }
                result[pkg] = InstalledApp(
                    pkg = pkg,
                    label = info.loadLabel(pm).toString().trim(),
                    banner = image.first,
                    icon = image.second,
                )
            }
        }
        // Сначала приложения для телевизора: у них правильный баннер и запуск.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)
        collect(Intent.CATEGORY_LAUNCHER)
        images.keys.retainAll(used)
        cached = result
        locale = currentLocale
        loadedGeneration = loadingGeneration
        return result
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        if (TvInputs.isInput(pkg)) return runCatching {
            val pm = context.packageManager
            val inputId = TvInputs.inputId(pkg)
            val info = context.getSystemService(TvInputManager::class.java)?.getTvInputInfo(inputId)
            val intents = if (info?.type == TvInputInfo.TYPE_TUNER) {
                // Эфир: URI списка каналов тюнера переключает приложение телевизора на антенну
                // (проверено на TCL); просто запустить приложение мало — оно вернёт последний вход.
                listOfNotNull(Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(inputId)), tvAppIntent(pm))
            } else {
                listOf(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)))
            }
            (intents + Intent("com.android.tv.action.VIEW_INPUTS"))
                .map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .firstOrNull { it.resolveActivity(pm) != null }
        }.getOrNull()
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Приложение телевизора — то, что умеет показывать каналы (на TCL — com.tcl.tv). */
    private fun tvAppIntent(pm: PackageManager): Intent? {
        val probe = Intent(Intent.ACTION_VIEW).setType(TvContract.Channels.CONTENT_ITEM_TYPE)
        val app = pm.queryIntentActivities(probe, 0).firstOrNull()?.activityInfo?.packageName ?: return null
        return pm.getLeanbackLaunchIntentForPackage(app) ?: pm.getLaunchIntentForPackage(app)
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        if (TvInputs.isInput(pkg)) {
            val info = context.getSystemService(TvInputManager::class.java)?.getTvInputInfo(TvInputs.inputId(pkg))
            info != null && TvInputs.isShown(context, info)
        } else {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }
    }.getOrDefault(false)

    internal fun Drawable.toImage(maxWidth: Int, maxHeight: Int): ImageBitmap? {
        if (this is BitmapDrawable && bitmap != null && bitmap.width <= maxWidth * 2 && bitmap.height <= maxHeight * 2) {
            return bitmap.asImageBitmap()
        }
        val w = intrinsicWidth.takeIf { it > 0 } ?: maxWidth
        val h = intrinsicHeight.takeIf { it > 0 } ?: maxHeight
        val scale = minOf(maxWidth.toFloat() / w, maxHeight.toFloat() / h, 2f)
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        return runCatching {
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            setBounds(0, 0, bw, bh)
            draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/** Фотографии для фона из assets/wallpapers. */
object Wallpapers {
    private const val DIR = "wallpapers"

    fun names(context: Context): List<String> =
        context.assets.list(DIR).orEmpty().filter { it.endsWith(".jpg") }.sorted()

    /** Картинка в 16-битном цвете: вдвое меньше памяти, на фоне под затемнением разницы не видно. */
    fun load(context: Context, name: String, width: Int = context.resources.displayMetrics.widthPixels, height: Int = context.resources.displayMetrics.heightPixels): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open("$DIR/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val size = wallpaperSize(bounds.outWidth, bounds.outHeight, width, height)
        context.assets.open("$DIR/$name").use { stream ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = size.sample
                inScaled = true
                inDensity = size.density
                inTargetDensity = size.targetDensity
            }
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }.getOrNull()

    /** Уменьшенная копия для галереи: каждая [sample]-я точка по обеим осям. */
    fun thumb(context: Context, name: String, sample: Int): ImageBitmap? = runCatching {
        context.assets.open("$DIR/$name").use { stream ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sample
            }
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }.getOrNull()
}
