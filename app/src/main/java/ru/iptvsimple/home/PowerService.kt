package ru.iptvsimple.home

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Служба специальных возможностей — единственный способ для обычного приложения усыпить приставку
 * или открыть системное меню питания. События экрана она не читает (см. res/xml/power_service.xml).
 * Включается один раз: Настройки → Специальные возможности → Просто Главная.
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

        /** Системное меню питания: перезагрузка и выключение. */
        fun powerDialog(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true
    }
}
