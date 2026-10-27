package ru.iptvsimple.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
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
 * Движение плитки в фокусе — по образцу Apple TV:
 * - [lift] — подъём: плитка пружиной увеличивается, всплывает и отбрасывает мягкую тень;
 * - [press] — при нажатии ОК плитка «вдавливается» и пружинит обратно;
 * - [tiltX]/[tiltY] — получив фокус, плитка качается в сторону движения и успокаивается;
 * - [sheen] — по стеклу один раз пробегает блик.
 */
@Stable
class FocusMotion(
    val lift: State<Float>,
    val press: State<Float>,
    val tiltX: Animatable<Float, *>,
    val tiltY: Animatable<Float, *>,
    val sheen: Animatable<Float, *>,
)

private val SheenEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

@Composable
fun rememberFocusMotion(focused: Boolean, pressed: Boolean): FocusMotion {
    // Небольшой «перелёт» (затухание 0.62) даёт живой, а не механический подъём.
    val lift = animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 360f), label = "lift")
    val press = animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 1100f), label = "press")
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val sheen = remember { Animatable(-1f) }
    LaunchedEffect(focused) {
        if (!focused) {
            tiltX.animateTo(0f, spring(stiffness = 300f))
            tiltY.animateTo(0f, spring(stiffness = 300f))
            return@LaunchedEffect
        }
        launch {
            tiltX.snapTo(NavDirection.dx * 9f)
            tiltX.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 170f))
        }
        launch {
            tiltY.snapTo(-NavDirection.dy * 7f)
            tiltY.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 170f))
        }
        launch {
            sheen.snapTo(-0.5f)
            sheen.animateTo(1.5f, tween(1100, delayMillis = 90, easing = SheenEasing))
        }
    }
    return remember(lift, press, tiltX, tiltY, sheen) { FocusMotion(lift, press, tiltX, tiltY, sheen) }
}

/**
 * Применяет [FocusMotion] к плитке: масштаб [maxScale] в фокусе, подъём, тень, наклон и блик.
 * Ставится до clip/background плитки.
 */
fun Modifier.focusLift(motion: FocusMotion, shape: Shape, maxScale: Float = 1.12f): Modifier = this
    .graphicsLayer {
        val lift = motion.lift.value
        val s = 1f + (maxScale - 1f) * lift - 0.07f * motion.press.value
        scaleX = s
        scaleY = s
        translationY = -5.dp.toPx() * lift + 2.dp.toPx() * motion.press.value
        rotationY = motion.tiltX.value
        rotationX = motion.tiltY.value
        cameraDistance = 10f * density
        this.shadowElevation = 22.dp.toPx() * lift * (1f - 0.5f * motion.press.value)
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
            0f to Color.White.copy(alpha = 0.16f * lift),
            0.5f to Color.Transparent,
        ),
    )
    // Пробегающий наискось блик.
    val p = motion.sheen.value
    if (p > -0.5f && p < 1.5f) {
        val w = size.width
        val center = w * p
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.45f to Color.White.copy(alpha = 0.0f),
                0.5f to Color.White.copy(alpha = 0.26f * lift),
                0.55f to Color.White.copy(alpha = 0.0f),
                1f to Color.Transparent,
                start = Offset(center - w * 0.6f, 0f),
                end = Offset(center + w * 0.6f, size.height),
            ),
        )
    }
}
