package ru.iptvsimple.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Движение элемента в фокусе — по образцу tvOS:
 * - [lift] — подъём: элемент плавно увеличивается, всплывает и отбрасывает мягкую тень;
 * - [press] — при нажатии ОК элемент «вдавливается» и возвращается;
 * - [tiltX]/[tiltY] — получив фокус, плитка слегка качается в сторону движения и успокаивается;
 * - [sheen] — по стеклу один раз медленно проходит блик.
 */
@Stable
class FocusMotion(
    val lift: State<Float>,
    val press: State<Float>,
    val tiltX: Animatable<Float, *>,
    val tiltY: Animatable<Float, *>,
    val sheen: Animatable<Float, *>,
)

/** Кривая «ease out» Apple: быстрый старт, долгое мягкое торможение. */
val AppleEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Пружина подъёма: без «перелёта», около 0,4 с — как фокус на Apple TV. */
fun <T> liftSpring() = spring<T>(dampingRatio = 0.9f, stiffness = 120f)

@Composable
fun rememberFocusMotion(focused: Boolean, pressed: Boolean): FocusMotion {
    val lift = animateFloatAsState(if (focused) 1f else 0f, liftSpring(), label = "lift")
    val press = animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 500f), label = "press")
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val sheen = remember { Animatable(-1f) }
    LaunchedEffect(focused) {
        if (!focused) {
            launch { tiltX.animateTo(0f, spring(stiffness = 120f)) }
            launch { tiltY.animateTo(0f, spring(stiffness = 120f)) }
            return@LaunchedEffect
        }
        launch {
            tiltX.snapTo(NavDirection.dx * 4f)
            tiltX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 70f))
        }
        launch {
            tiltY.snapTo(-NavDirection.dy * 3f)
            tiltY.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 70f))
        }
        launch {
            sheen.snapTo(-0.5f)
            sheen.animateTo(1.5f, tween(1700, delayMillis = 180, easing = AppleEase))
        }
    }
    return remember(lift, press, tiltX, tiltY, sheen) { FocusMotion(lift, press, tiltX, tiltY, sheen) }
}

/**
 * Применяет [FocusMotion]: масштаб [maxScale] в фокусе, подъём, тень и наклон.
 * Ставится до clip/background элемента.
 */
fun Modifier.focusLift(motion: FocusMotion, shape: Shape, maxScale: Float = 1.1f, shadow: Boolean = true): Modifier = this
    .graphicsLayer {
        val lift = motion.lift.value
        val s = 1f + (maxScale - 1f) * lift - 0.04f * motion.press.value
        scaleX = s
        scaleY = s
        translationY = -4.dp.toPx() * lift + 1.dp.toPx() * motion.press.value
        rotationY = motion.tiltX.value
        rotationX = motion.tiltY.value
        cameraDistance = 12f * density
        if (shadow) shadowElevation = 18.dp.toPx() * lift.coerceIn(0f, 1f) * (1f - 0.4f * motion.press.value)
        this.shape = shape
        clip = false
        ambientShadowColor = Color.Black
        spotShadowColor = Color.Black
    }

/** Блик и верхний отсвет поверх содержимого плитки. Ставится после clip. */
fun Modifier.focusGloss(motion: FocusMotion): Modifier = drawWithContent {
    drawContent()
    val lift = motion.lift.value.coerceIn(0f, 1f)
    if (lift <= 0.01f) return@drawWithContent
    // Мягкий свет сверху — стекло «ловит» лампу.
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f * lift),
            0.55f to Color.Transparent,
        ),
    )
    // Медленно проходящий наискось широкий блик.
    val p = motion.sheen.value
    if (p > -0.5f && p < 1.5f) {
        val w = size.width
        val center = w * p
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.38f to Color.White.copy(alpha = 0.0f),
                0.5f to Color.White.copy(alpha = 0.16f * lift),
                0.62f to Color.White.copy(alpha = 0.0f),
                1f to Color.Transparent,
                start = Offset(center - w * 0.7f, 0f),
                end = Offset(center + w * 0.7f, size.height),
            ),
        )
    }
}

/** Покачивание при перестановке — как значки на Apple TV в режиме «Переместить». */
@Composable
fun Modifier.wiggle(active: Boolean): Modifier {
    if (!active) return this
    val t = rememberInfiniteTransition(label = "wiggle")
    val angle = t.animateFloat(
        initialValue = -1.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse),
        label = "wiggleAngle",
    )
    return graphicsLayer { rotationZ = angle.value }
}
