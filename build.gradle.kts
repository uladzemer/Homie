// Корневой скрипт: только объявляет плагины, настройки модуля лежат в app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
