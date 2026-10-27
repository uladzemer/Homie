package ru.iptvsimple.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

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
    /** Все приложения, которые можно запустить. Долго (десятки картинок) — вызывать не с главного потока. */
    fun load(context: Context): Map<String, InstalledApp> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, InstalledApp>()
        fun collect(category: String) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in KnownApps.neverShown || pkg in result) continue
                val banner = runCatching { activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm) }
                    .getOrNull()
                val icon = runCatching { activity.loadIcon(pm) }.getOrNull()
                result[pkg] = InstalledApp(
                    pkg = pkg,
                    label = info.loadLabel(pm).toString().trim(),
                    banner = banner?.toImage(320, 180),
                    icon = if (banner == null) icon?.toImage(160, 160) else null,
                )
            }
        }
        // Сначала приложения для телевизора: у них правильный баннер и запуск.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)
        collect(Intent.CATEGORY_LAUNCHER)
        return result
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    private fun Drawable.toImage(maxWidth: Int, maxHeight: Int): ImageBitmap? {
        if (this is BitmapDrawable && bitmap != null && bitmap.width <= maxWidth * 2) {
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
    fun load(context: Context, name: String): ImageBitmap? = runCatching {
        context.assets.open("$DIR/$name").use { stream ->
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }.getOrNull()
}
