import java.util.Properties

// Приложение «Просто Главная» — главный экран (лаунчер) для приставок Android TV.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Ключ подписи релиза в репозиторий не кладётся: путь к файлу с параметрами
// задаётся в ~/.gradle/gradle.properties (prostoHomeSigningProps=/путь/к/файлу.properties)
// или через переменные окружения RELEASE_*. Без ключа релиз собирается неподписанным.
val releaseSigningProps = Properties().apply {
    providers.gradleProperty("prostoHomeSigningProps").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?.takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}
fun releaseSigningValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: releaseSigningProps.getProperty(key)
val releaseStoreFile = releaseSigningValue("RELEASE_KEYSTORE_FILE", "storeFile")

val appVersionName = "0.1.0"
base.archivesName = "prosto-home-$appVersionName"

android {
    namespace = "ru.iptvsimple.home"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.iptvsimple.home"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseSigningValue("RELEASE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = releaseSigningValue("RELEASE_KEY_ALIAS", "keyAlias")
                keyPassword = releaseSigningValue("RELEASE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
        // Обои уже сжаты в JPEG, пересжимать их при упаковке бессмысленно.
        noCompress += "jpg"
    }

    dependenciesInfo {
        // Зашифрованный список зависимостей для Google Play в APK не кладём (требование F-Droid).
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons)
    implementation(libs.tv.material)

    testImplementation(libs.kotlin.test)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
