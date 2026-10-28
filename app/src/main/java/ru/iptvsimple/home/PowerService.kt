package ru.iptvsimple.home

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Служба специальных возможностей — единственный способ для обычного приложения усыпить приставку
 * или открыть системное меню питания. События экрана она не читает (см. res/xml/power_service.xml).
 * Включается один раз: Настройки → Специальные возможности → Homie.
 *
 * Некоторые прошивки сами выключают службы: при обновлении приложения, а прошивка приставок P7
 * (Allwinner) — при каждой смене окна, ради Netflix. Тогда один раз по ADB
 * `pm grant ru.iptvsimple.home android.permission.WRITE_SECURE_SETTINGS`: Homie включит службу сам
 * в момент нажатия кнопки.
 */
class PowerService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: PowerService? = null

        /** Кнопками питания можно пользоваться: служба включена или Homie может включить её сам. */
        fun isAvailable(context: Context): Boolean = instance != null || canEnableItself(context)

        /** Спящий режим: экран гаснет, приставка просыпается с пульта. */
        val canSleep: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        fun sleep(context: Context) {
            if (canSleep) perform(context, GLOBAL_ACTION_LOCK_SCREEN)
        }

        /** Системное меню питания: перезагрузка и выключение. */
        fun powerDialog(context: Context) = perform(context, GLOBAL_ACTION_POWER_DIALOG)

        private fun canEnableItself(context: Context) =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /**
         * Выполняет действие, при необходимости сначала включив службу. Начинаем с паузы: меню ещё
         * закрывается, а смена окна на P7 сбрасывает службы. Пока служба не подключилась — повторяем.
         */
        private fun perform(context: Context, action: Int) {
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            val deadline = SystemClock.uptimeMillis() + 3000
            handler.postDelayed(object : Runnable {
                override fun run() {
                    ensureEnabled(app)
                    if (instance?.performGlobalAction(action) == true) return
                    if (SystemClock.uptimeMillis() < deadline) handler.postDelayed(this, 100)
                }
            }, 300)
        }

        /** Включает службу в настройках, если выдано WRITE_SECURE_SETTINGS. Без него ничего не делает. */
        private fun ensureEnabled(context: Context) {
            if (!canEnableItself(context)) return
            val me = ComponentName(context, PowerService::class.java)
            val resolver = context.contentResolver
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val listed = enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
            if (listed && Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return
            runCatching {
                if (!listed) {
                    val updated = listOf(enabled, me.flattenToString()).filter { it.isNotEmpty() }.joinToString(":")
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                }
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }
    }
}
