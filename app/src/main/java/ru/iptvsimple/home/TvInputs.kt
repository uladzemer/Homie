package ru.iptvsimple.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Входы телевизора (HDMI, эфир) в общем списке плиток. Это не пакеты приложений: id плитки —
 * `input:` + id входа из [TvInputManager]. Баннер рисуем сами в духе Apple TV: градиентная
 * подложка, белый значок и крупная подпись — системные значки входов на телевизорах невзрачные.
 */
object TvInputs {
    const val PREFIX = "input:"

    fun isInput(id: String): Boolean = id.startsWith(PREFIX)

    fun inputId(id: String): String = id.removePrefix(PREFIX)

    /** Доступные входы, отсортированные по названию («HDMI 1, HDMI 2, …, TV»). */
    fun load(context: Context): List<InstalledApp> = runCatching {
        context.getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty().mapNotNull { info ->
            runCatching input@{
                if (!isShown(context, info)) return@input null
                val label = runCatching { info.loadLabel(context)?.toString()?.trim() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.tv_input)
                InstalledApp(pkg = PREFIX + info.id, label = label, banner = banner(context, info.type, label), icon = null)
            }.getOrNull()
        }.sortedBy { it.label }
    }.getOrDefault(emptyList())

    /**
     * Вход — аппаратный (разъём или тюнер телевизора) и не скрыт пользователем в настройках.
     * Программные источники каналов (IPTV-приложения с TvInputService) — не входы, у них
     * есть свои плитки. Аппаратные входы система именует `пакет/.Служба/HW<n>` или `/HDMI<n>`.
     */
    fun isShown(context: Context, info: TvInputInfo): Boolean =
        !info.isHidden(context) && isHardware(info.id)

    fun isHardware(inputId: String): Boolean =
        inputId.substringAfterLast('/').let { it.startsWith("HW") || it.startsWith("HDMI") }

    /** Баннер 320×180: диагональный градиент, значок сверху, подпись снизу. */
    private fun banner(context: Context, type: Int, label: String) = runCatching {
        val w = 320
        val h = 180
        val (icon, top, bottom) = when (type) {
            TvInputInfo.TYPE_TUNER -> Triple(R.drawable.ic_input_tv, 0xFF0F766E.toInt(), 0xFF2DD4BF.toInt())
            TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_DISPLAY_PORT, TvInputInfo.TYPE_DVI ->
                Triple(R.drawable.ic_input_hdmi, 0xFF312E81.toInt(), 0xFF6366F1.toInt())
            else -> Triple(R.drawable.ic_input_av, 0xFF374151.toInt(), 0xFF6B7280.toInt())
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        // Лёгкий блик сверху, как на плитках Apple TV.
        canvas.drawRect(0f, 0f, w.toFloat(), h / 2f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h / 2f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        // Только значок по центру: название и так подписано под плиткой (решение владельца 2026-10-31).
        context.getDrawable(icon)?.let {
            val size = 104
            it.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
            it.alpha = 240
            it.draw(canvas)
        }
        // Номер порта («HDMI 1» → «1») — мелко в углу, чтобы входы различались и без фокуса.
        label.trim().takeLastWhile { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 2 }?.let { number ->
            canvas.drawText(number, w - 18f, h - 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xD9FFFFFF.toInt()
                textSize = 26f
                textAlign = Paint.Align.RIGHT
                typeface = typeface(context)
            })
        }
        bitmap.asImageBitmap()
    }.getOrNull()

    private fun typeface(context: Context): Typeface = runCatching {
        val inter = context.resources.getFont(R.font.inter)
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(inter, 600, false) else Typeface.create(inter, Typeface.BOLD)
    }.getOrDefault(Typeface.DEFAULT_BOLD)
}
