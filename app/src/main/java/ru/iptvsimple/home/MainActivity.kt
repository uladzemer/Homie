package ru.iptvsimple.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.em
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Главный экран приставки. Один экран, настройки открываются поверх него. */
class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private var config by mutableStateOf<HomeConfig?>(null)
    private var apps by mutableStateOf<Map<String, InstalledApp>>(emptyMap())
    private var reloadTick by mutableIntStateOf(0)
    private var homeTick by mutableIntStateOf(0)
    private var showSettings by mutableStateOf(false)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { reloadTick++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        config = store.load()
        if (savedInstanceState == null) autostartAfterBoot()

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })

        setContent {
            ScreenDensity {
                MaterialTheme(colorScheme = darkColorScheme(background = Palette.Window, surface = Palette.Card)) {
                    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = AppFont, letterSpacing = (-0.01).em)) {
                        Root()
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Запоминаем направление: плитка, получившая фокус, качнётся в его сторону.
        if (event.action == android.view.KeyEvent.ACTION_DOWN) when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { NavDirection.dx = -1; NavDirection.dy = 0 }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { NavDirection.dx = 1; NavDirection.dy = 0 }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> { NavDirection.dx = 0; NavDirection.dy = -1 }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { NavDirection.dx = 0; NavDirection.dy = 1 }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Нажали «Домой», когда главный экран уже открыт: закрываем настройки, фокус на первую плитку.
        showSettings = false
        homeTick++
    }

    override fun onResume() {
        super.onResume()
        // Пока были в другом приложении, могли что-то установить из магазина.
        reloadTick++
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    @Composable
    private fun Root() {
        val context = LocalContext.current
        LaunchedEffect(reloadTick) {
            val loaded = withContext(Dispatchers.IO) { InstalledApps.load(context) }
            apps = loaded
            if (config == null) applyConfig(HomeConfig.initial(loaded.keys))
        }
        val photos = remember { Wallpapers.names(context) }
        val current = config
        var deep by remember { mutableStateOf(false) }

        BackHandler(enabled = showSettings) { showSettings = false }
        // На главном экране «Назад» ничего не делает: выходить некуда.
        BackHandler(enabled = !showSettings) { }

        CompositionLocalProvider(LocalTextScale provides (current?.textSize?.scale ?: 1f)) {
            Box(Modifier.fillMaxSize().background(Palette.Window)) {
                if (current != null) {
                    if (current.style == HomeStyle.Classic) {
                        CarouselScreen(
                            config = current,
                            apps = apps,
                            focusHome = homeTick,
                            onConfig = ::applyConfig,
                            onLaunch = ::launch,
                            onOpenSettings = { showSettings = true },
                        )
                    } else {
                        BackdropLayer(current, photos, dim = deep && !showSettings)
                        HomeScreen(
                            config = current,
                            apps = apps,
                            focusHome = homeTick,
                            onConfig = ::applyConfig,
                            onLaunch = ::launch,
                            onOpenSettings = { showSettings = true },
                            onDeepFocus = { deep = it },
                        )
                    }
                    // Панель настроек выезжает слева с затуханием.
                    AnimatedVisibility(
                        visible = showSettings,
                        enter = fadeIn(tween(380, easing = AppleEase)) + slideInHorizontally(liftSpring()) { it / 16 },
                        exit = fadeOut(tween(260, easing = AppleEase)) + slideOutHorizontally(tween(300, easing = AppleEase)) { it / 20 },
                    ) {
                        SettingsScreen(
                            config = current,
                            apps = apps,
                            photoCount = photos.size,
                            versionName = versionName(),
                            onConfig = ::applyConfig,
                            onSystemSettings = { launchSettings(this@MainActivity, Settings.ACTION_SETTINGS) },
                        )
                    }
                }
            }
        }
        // После закрытия настроек фокус возвращается на главный экран.
        LaunchedEffect(showSettings) { if (!showSettings) homeTick++ }
    }

    private fun applyConfig(value: HomeConfig) {
        config = value
        store.save(value)
    }

    private fun launch(pkg: String) {
        val intent = InstalledApps.launchIntent(this, pkg) ?: return
        runCatching { startActivity(intent) }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    /**
     * Автозапуск выбранного приложения — один раз за включение приставки. Главный экран и так
     * запускается системой после загрузки, поэтому отдельная служба не нужна. Если оболочку
     * перезапустили посреди работы (нехватка памяти), повторно приложение не открываем.
     */
    private fun autostartAfterBoot() {
        val pkg = config?.autostart ?: return
        val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        // Без счётчика загрузок — время включения, округлённое до 10 минут.
        val boot = if (bootCount >= 0) "count:$bootCount"
        else "time:${(System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600_000L}"
        if (store.autostartBoot == boot) return
        store.autostartBoot = boot
        if (InstalledApps.isInstalled(this, pkg)) launch(pkg)
    }
}

/** Фон: фотографии природы, одна картинка или заливка; поверх — затемнение для читаемости. */
@Composable
private fun BackdropLayer(config: HomeConfig, photos: List<String>, dim: Boolean) {
    val context = LocalContext.current
    when (config.backdrop) {
        Backdrop.Dark -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1C1C1E), Color(0xFF000000)))))
        Backdrop.Blue -> Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2453A8), Color(0xFF0B1F4A)))),
        )
        Backdrop.PhotosRotating, Backdrop.PhotoFixed -> {
            if (photos.isEmpty()) return
            var index by remember { mutableIntStateOf(config.photoIndex.coerceIn(0, photos.lastIndex)) }
            if (config.backdrop == Backdrop.PhotoFixed) index = config.photoIndex.coerceIn(0, photos.lastIndex)
            LaunchedEffect(config.backdrop) {
                while (config.backdrop == Backdrop.PhotosRotating) {
                    delay(10 * 60_000L)
                    index = (index + 1) % photos.size
                }
            }
            val bitmap by produceState<ImageBitmap?>(null, index) {
                value = withContext(Dispatchers.IO) { Wallpapers.load(context, photos[index]) }
            }
            Crossfade(bitmap, animationSpec = tween(1500), label = "wallpaper") { image ->
                if (image != null) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
    // Затемнение: сверху и снизу сильнее (часы и плитки), при прокрутке вниз — всё темнее.
    val extra by animateFloatAsState(if (dim) 1f else 0f, tween(600, easing = AppleEase), label = "dim")
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.35f to Color.Black.copy(alpha = 0.12f),
                    1f to Color.Black.copy(alpha = 0.65f),
                ),
            ),
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = extra }.background(Color.Black.copy(alpha = 0.45f)))
}
