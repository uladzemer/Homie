package ru.iptvsimple.home

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Служба специальных возможностей — единственный способ для обычного приложения усыпить приставку
 * или открыть системное меню питания. События экрана она не читает (см. res/xml/power_service.xml).
 * Включается один раз: Настройки → Специальные возможности → Homie.
 * Некоторые прошивки выключают службу при каждом обновлении приложения — тогда один раз по ADB
 * `pm grant ru.iptvsimple.home android.permission.WRITE_SECURE_SETTINGS`, и [ensureEnabled] вернёт её сам.
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

        val isEnabled: Boolean get() = instance != null

        /** Спящий режим: экран гаснет, приставка просыпается с пульта. */
        val canSleep: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        fun sleep(): Boolean =
            canSleep && instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        /** Включает службу сама, если по ADB выдано WRITE_SECURE_SETTINGS. Без него ничего не делает. */
        fun ensureEnabled(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
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

        /** Системное меню питания: перезагрузка и выключение. */
        fun powerDialog(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true
    }
}
