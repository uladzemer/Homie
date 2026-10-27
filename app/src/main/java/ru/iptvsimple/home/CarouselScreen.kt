package ru.iptvsimple.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Ряд карусели: ряд пользователя или «Остальные приложения». */
private class CarouselShelf(val title: String, val rowId: Long?, val apps: List<InstalledApp>)

/**
 * Классический вид: обложки приложений листаются каруселью — выбранная в центре, соседние
 * развёрнуты в глубину, под ними отражение на глянцевом полу. Сверху — выпуклые «стеклянные»
 * кнопки рядов. ◀ ▶ — листать, ▲ ▼ — другой ряд, ОК — открыть, долгое ОК — меню.
 */
@Composable
fun CarouselScreen(
    config: HomeConfig,
    apps: Map<String, InstalledApp>,
    focusHome: Int,
    onConfig: (HomeConfig) -> Unit,
    onLaunch: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val shelves = buildList {
        config.rows.forEach { row ->
            val shown = row.apps.mapNotNull { apps[it] }
            if (shown.isNotEmpty()) add(CarouselShelf(row.title, row.id, shown))
        }
        if (config.showOthers) {
            val others = apps.values
                .filter { it.pkg !in config.placed && it.pkg !in config.hidden }
                .sortedBy { it.label.lowercase() }
            if (others.isNotEmpty()) add(CarouselShelf(if (isEmpty()) "Все приложения" else "Остальные", null, others))
        }
    }

    var shelfIndex by remember { mutableIntStateOf(0) }
    // Выбранная обложка в каждом ряду запоминается по пакету: после перестановки она остаётся в центре.
    val selected = remember { mutableStateMapOf<Int, String>() }
    var moving by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val carouselRequester = remember { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }

    shelfIndex = shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0))
    val shelf = shelves.getOrNull(shelfIndex)
    val index = shelf?.apps?.indexOfFirst { it.pkg == selected[shelfIndex] }?.takeIf { it >= 0 } ?: 0
    val current = shelf?.apps?.getOrNull(index)

    LaunchedEffect(focusHome, apps.isNotEmpty()) {
        if (apps.isEmpty()) return@LaunchedEffect
        moving = false
        shelfIndex = 0
        withFrameNanos { }
        runCatching { (if (shelves.isNotEmpty()) carouselRequester else settingsRequester).requestFocus() }
    }

    fun select(i: Int) {
        val list = shelf?.apps ?: return
        selected[shelfIndex] = list[i.coerceIn(0, list.lastIndex)].pkg
    }

    fun move(dx: Int, dy: Int) {
        val pkg = current?.pkg ?: return
        val updated = config.moveApp(pkg, dx, dy) ?: return
        onConfig(updated)
        if (dy != 0) {
            shelfIndex = (shelfIndex + dy).coerceAtLeast(0)
            selected[shelfIndex] = pkg
        }
    }

    Box(Modifier.fillMaxSize()) {
        ClassicBackdrop()
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 26.dp)) {
                if (config.showClock) ClassicClock()
                StatusBar(settingsRequester, onOpenSettings, Modifier.align(Alignment.TopEnd))
            }
            // Кнопки рядов: выбранный ряд — голубое выпуклое стекло.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                shelves.forEachIndexed { i, s -> GlassCapsule(s.title, active = i == shelfIndex) }
            }
        }

        val coverWidth = (config.tileSize.width * 1.7f).dp
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 160.dp)
                .height(coverWidth * 9f / 16f * 1.7f + 90.dp)
                .focusRequester(carouselRequester)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (shelf == null) return@onKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    if (moving) {
                        if (e.type == KeyEventType.KeyDown) when (code) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
                        }
                        if (e.type == KeyEventType.KeyUp && code in FinishKeys) moving = false
                        return@onKeyEvent true
                    }
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (e.type == KeyEventType.KeyDown) select(index - 1); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.type == KeyEventType.KeyDown) select(index + 1); true }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (e.type == KeyEventType.KeyDown && shelfIndex < shelves.lastIndex) shelfIndex++
                            true
                        }
                        // С первого ряда «вверх» — на кнопки сети, питания и настроек.
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (shelfIndex == 0) return@onKeyEvent false
                            if (e.type == KeyEventType.KeyDown) shelfIndex--
                            true
                        }
                        else -> false
                    }
                }
                .then(confirmHandler(onClick = { current?.let { onLaunch(it.pkg) } }, onLong = { current?.let { menuFor = it.pkg } }, onPress = { pressed = it }, enabled = !moving))
                .focusable(),
        ) {
            // Смена ряда: новая карусель всплывает снизу или сверху, старая уходит.
            AnimatedContent(
                targetState = shelfIndex,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(420, easing = AppleEase)) + slideInVertically(liftSpring()) { dir * it / 6 }) togetherWith
                        (fadeOut(tween(260)) + slideOutVertically(tween(340, easing = AppleEase)) { -dir * it / 8 })
                },
                label = "shelf",
            ) { i ->
                val s = shelves.getOrNull(i)
                if (s != null) {
                    val idx = s.apps.indexOfFirst { it.pkg == selected[i] }.takeIf { it >= 0 } ?: 0
                    Carousel(s.apps, idx, coverWidth, highlight = focused, moving = moving, pressed = pressed)
                }
            }
        }

        // Название выбранного приложения и подсказка.
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (current != null) {
                Text(
                    current.label,
                    color = Palette.Ink,
                    fontSize = ts(22f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (moving) "Стрелками — переставить, ОК — готово" else "${index + 1} из ${shelf.apps.size}",
                    color = if (moving) Palette.InkSoft else Palette.InkMuted,
                    fontSize = ts(12f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (apps.isEmpty()) {
                Text("Загружаю приложения…", color = Palette.InkSoft, fontSize = ts(14f))
            }
        }
    }

    menuFor?.let { pkg ->
        TileMenu(
            pkg = pkg,
            config = config,
            apps = apps,
            onConfig = onConfig,
            onLaunch = onLaunch,
            onMove = { moving = true },
            onFocus = { },
            onDismiss = {
                menuFor = null
                runCatching { carouselRequester.requestFocus() }
            },
        )
    }
}

private val FinishKeys = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_BUTTON_A, AndroidKeyEvent.KEYCODE_MENU,
)

/** ОК — [onClick] при отпускании, долгое ОК или «Меню» — [onLong]; как в [FocusBox]. */
@Composable
private fun confirmHandler(onClick: () -> Unit, onLong: () -> Unit, onPress: (Boolean) -> Unit, enabled: Boolean): Modifier {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) { onPress(pressed) }
    var longFired by remember { mutableStateOf(false) }
    return Modifier.onKeyEvent { e ->
        if (!enabled) return@onKeyEvent false
        val code = e.nativeKeyEvent.keyCode
        if (code == AndroidKeyEvent.KEYCODE_MENU) {
            if (e.type == KeyEventType.KeyUp) onLong()
            return@onKeyEvent true
        }
        if (code !in FinishKeys || code == AndroidKeyEvent.KEYCODE_BACK) return@onKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) {
                pressed = true
                longFired = false
            } else if (pressed && !longFired) {
                longFired = true
                onLong()
            }
            KeyEventType.KeyUp -> {
                if (pressed && !longFired) onClick()
                pressed = false
            }
        }
        true
    }
}

/** Сама карусель: обложки по обе стороны от выбранной, плавно доезжают при листании. */
@Composable
private fun Carousel(items: List<InstalledApp>, index: Int, coverWidth: Dp, highlight: Boolean, moving: Boolean, pressed: Boolean) {
    val position by animateFloatAsState(
        index.toFloat(),
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "carousel",
    )
    val coverHeight = coverWidth * 9f / 16f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val first = (position - 5).roundToInt().coerceAtLeast(0)
        val last = (position + 5).roundToInt().coerceAtMost(items.lastIndex)
        for (k in first..last) {
            val d = k - position
            val ad = abs(d)
            // Центр — плоско и крупно; соседние сдвинуты в стороны, повёрнуты и плотно уложены.
            val x = if (ad < 1f) d * coverWidth.value * 0.78f else sign(d) * (coverWidth.value * 0.78f + (ad - 1f) * coverWidth.value * 0.34f)
            val rotation = -d.coerceIn(-1f, 1f) * 58f
            val scale = 1f - 0.18f * ad.coerceAtMost(1f)
            val app = items[k]
            key(app.pkg) {
            Column(
                Modifier
                    .zIndex(10f - ad)
                    .offset(x = x.dp)
                    .graphicsLayer {
                        rotationY = rotation
                        scaleX = scale
                        scaleY = scale
                        cameraDistance = 14f * density
                        alpha = (1.2f - ad * 0.16f).coerceIn(0f, 1f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val isCenter = ad < 0.5f
                // Выбранная обложка — с тем же подъёмом, бликом и нажатием, что плитки.
                val motion = rememberFocusMotion(focused = isCenter && highlight, pressed = isCenter && pressed)
                Box(Modifier.wiggle(isCenter && moving)) { Cover(app, coverWidth, coverHeight, motion) }
                Reflection(app, coverWidth, coverHeight)
            }
            }
        }
    }
}

/** Обложка со стеклянным бликом сверху — выпуклая, как в старых системах. */
@Composable
private fun Cover(app: InstalledApp, width: Dp, height: Dp, motion: FocusMotion) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(width, height)
            .focusLift(motion, shape, maxScale = 1.05f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)))),
        contentAlignment = Alignment.Center,
    ) {
        CoverImage(app)
        // Блик: светлая полоса на верхней половине, мягко гаснет к середине.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.34f),
                        0.46f to Color.White.copy(alpha = 0.08f),
                        0.5f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(Modifier.fillMaxSize().focusGloss(motion))
    }
}

@Composable
private fun CoverImage(app: InstalledApp) {
    when {
        app.banner != null -> Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        app.icon != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(app.icon, null, Modifier.size(52.dp))
            Text(app.label, color = Palette.Ink, fontSize = ts(12f), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
        else -> Text(app.label, color = Palette.Ink, fontSize = ts(14f))
    }
}

/** Отражение обложки на полу: перевёрнуто и гаснет книзу. */
@Composable
private fun Reflection(app: InstalledApp, width: Dp, height: Dp) {
    Box(
        Modifier
            .padding(top = 3.dp)
            .size(width, height * 0.55f)
            .clip(RoundedCornerShape(14.dp))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent),
                        startY = 0f,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                    scaleY = -1f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                    translationY = 0f
                }
                .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)))),
            contentAlignment = Alignment.Center,
        ) { CoverImage(app) }
    }
}

/** Чёрная сцена с прожектором сверху и глянцевым полом. */
@Composable
private fun ClassicBackdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0D1422), Color(0xFF020306)))),
    )
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x3380A8FF), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = size.width * 0.55f,
                    ),
                )
                // Линия горизонта пола.
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.6f to Color.Transparent,
                        0.605f to Color(0x1AFFFFFF),
                        0.62f to Color(0x06FFFFFF),
                        1f to Color.Transparent,
                    ),
                )
            },
    )
}

@Composable
private fun ClassicClock() {
    // Те же часы, что в обычном виде, — с лёгким «металлическим» отливом.
    Box(Modifier.graphicsLayer { alpha = 0.95f }) { HomeClock() }
}

/** Выпуклая стеклянная капсула: голубая у выбранного ряда, дымчатая у остальных. */
@Composable
private fun GlassCapsule(title: String, active: Boolean) {
    val shape = RoundedCornerShape(50)
    val base = if (active) listOf(Color(0xFF7CC4FF), Color(0xFF2A7BE0), Color(0xFF1857B8))
    else listOf(Color(0xFF5A6474), Color(0xFF2C333F), Color(0xFF1C2129))
    Box(
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(base)),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.55f),
                        0.45f to Color.White.copy(alpha = 0.12f),
                        0.5f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )
        Text(
            title,
            color = Color.White,
            fontSize = ts(13f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
}
