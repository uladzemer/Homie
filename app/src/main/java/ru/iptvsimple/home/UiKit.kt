package ru.iptvsimple.home

import androidx.compose.ui.res.stringResource
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import kotlin.math.min

/** Цвета тёмной темы tvOS: нейтральные серые, без рамок; выбранное — белое. */
object Palette {
    val Window = Color(0xFF000000)
    val Ink = Color(0xFFFFFFFF)
    val InkSoft = Color(0xB3FFFFFF)
    val InkMuted = Color(0x80FFFFFF)
    val Card = Color(0xFF2C2C2E)
    val Panel = Color(0xF21C1C1E)
    /** Невыбранная кнопка: полупрозрачная подложка («материал»). */
    val Fill = Color(0x1FFFFFFF)
    /** Кнопки поверх фотографии — темнее, чтобы читались на светлом фоне. */
    val Pill = Color(0x80202022)
    val Focus = Color(0xFFFFFFFF)
    val OnFocus = Color(0xFF1C1C1E)
    val Red = Color(0xFFFF453A)
}

/**
 * Шрифт Inter (SIL OFL) — открытая замена SF Pro, которую Apple разрешает только на своих устройствах.
 * Один файл с переменной насыщенностью.
 */
@OptIn(ExperimentalTextApi::class)
val AppFont = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(R.font.inter, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    },
)

/** Цвет кнопки tvOS: белая в фокусе, полупрозрачная без него; смена — плавная. */
@Composable
fun buttonColors(focused: Boolean, rest: Color = Palette.Fill): Pair<Color, Color> {
    val bg by animateColorAsState(if (focused) Palette.Focus else rest, tween(260, easing = AppleEase), label = "buttonBg")
    val ink by animateColorAsState(if (focused) Palette.OnFocus else Palette.Ink, tween(260, easing = AppleEase), label = "buttonInk")
    return bg to ink
}

/** Множитель надписей из настроек; размеры шрифтов умножаются на него через [ts]. */
val LocalTextScale = compositionLocalOf { 1f }

@Composable
fun ts(size: Float): TextUnit = (size * LocalTextScale.current).sp

/** Расчётный экран: 960×540 dp при любой плотности приставки. */
private const val DesignWidth = 960f
private const val DesignHeight = 540f

/**
 * Пересчитывает плотность так, чтобы экран был ровно 960×540 dp. Иначе на приставках
 * с нестандартной плотностью (например, 240 вместо 320 при 1080p) всё мельчает.
 */
@Composable
fun ScreenDensity(content: @Composable () -> Unit) {
    val system = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthPx = configuration.screenWidthDp * system.density
    val heightPx = configuration.screenHeightDp * system.density
    val density = if (widthPx > 0f && heightPx > 0f) {
        Density(min(widthPx / DesignWidth, heightPx / DesignHeight), system.fontScale)
    } else {
        system
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

private fun KeyEvent.isConfirm(): Boolean = when (nativeKeyEvent.keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> true
    else -> false
}

/**
 * Элемент, который выбирается пультом. ОК — [onClick]; долгое ОК или кнопка «Меню» — [onLongClick].
 * Нажатие засчитывается при отпускании и только если кнопку нажали на этом же элементе:
 * так отпускание ОК после долгого нажатия не срабатывает в открывшемся меню.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onPressChange: (Boolean) -> Unit = {},
    onKey: ((KeyEvent) -> Boolean)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) { onPressChange(pressed) }
    var longFired by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    if (!it.isFocused) pressed = false
                    onFocusChange(it.isFocused)
                }
            }
            .onKeyEvent { e ->
                if (onKey?.invoke(e) == true) return@onKeyEvent true
                val code = e.nativeKeyEvent.keyCode
                if (code == AndroidKeyEvent.KEYCODE_MENU && onLongClick != null) {
                    if (e.type == KeyEventType.KeyUp) onLongClick()
                    return@onKeyEvent true
                }
                if (!e.isConfirm()) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (repeat == 0) {
                            pressed = true
                            longFired = false
                        } else if (pressed && !longFired && onLongClick != null) {
                            longFired = true
                            onLongClick()
                        }
                    }
                    KeyEventType.KeyUp -> {
                        if (pressed && !longFired) onClick()
                        pressed = false
                    }
                }
                true
            }
            .focusable(),
        content = { content(focused) },
    )
}

/** Кнопка-«таблетка»: белая и приподнятая в фокусе. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Float = 13f,
) {
    var pressed by remember { mutableStateOf(false) }
    FocusBox(onClick = onClick, modifier = modifier, onPressChange = { pressed = it }) { focused ->
        val motion = rememberFocusMotion(focused, pressed)
        val (bg, ink) = buttonColors(focused, Palette.Pill)
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .focusLift(motion, shape, maxScale = 1.08f)
                .background(bg, shape)
                .padding(horizontal = 16.dp, vertical = 7.dp),
        ) {
            Text(
                text,
                color = ink,
                fontSize = ts(fontSize),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Пункт меню во всю ширину. */
@Composable
fun MenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    hint: String? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    FocusBox(onClick = onClick, modifier = modifier.fillMaxWidth(), onKey = onKey, onPressChange = { pressed = it }) { focused ->
        val motion = rememberFocusMotion(focused, pressed)
        val (bg, ink) = buttonColors(focused)
        val shape = RoundedCornerShape(12.dp)
        // Как строка настроек tvOS: название слева, значение справа серым.
        Row(
            Modifier
                .fillMaxWidth()
                .focusLift(motion, shape, maxScale = 1.03f)
                .heightIn(min = 44.dp)
                .background(bg, shape)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text, color = ink, fontSize = ts(15f), fontWeight = FontWeight.Medium)
                if (hint != null && focused) {
                    Text(hint, color = ink.copy(alpha = 0.5f), fontSize = ts(11.5f))
                }
            }
            if (value != null) {
                Text(
                    if (focused && onKey != null) "‹  $value  ›" else value,
                    color = ink.copy(alpha = 0.55f),
                    fontSize = ts(14f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 16.dp).widthIn(max = 260.dp),
                )
            }
        }
    }
}

/** Пункт меню: название и действие. */
class MenuItem(val text: String, val action: () -> Unit)

/**
 * Окно-меню поверх экрана. После выбора пункта выполняется действие, затем окно закрывается.
 * Действие может открыть следующее окно и не дать закрыть себя в [onDismiss]. «Назад» закрывает окно.
 */
@Composable
fun MenuDialog(
    title: String,
    items: List<MenuItem>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 420.dp,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogPanel(width) {
            Text(title, color = Palette.Ink, fontSize = ts(19f), fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = Palette.InkSoft, fontSize = ts(13f), modifier = Modifier.padding(top = 4.dp))
            }
            val first = remember { FocusRequester() }
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .verticalScroll(rememberScrollState())
                    // Запас, чтобы приподнятый пункт не обрезался краем списка.
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEachIndexed { index, item ->
                    MenuButton(
                        text = item.text,
                        onClick = {
                            item.action()
                            onDismiss()
                        },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

/** Окно ввода названия: открывается экранная клавиатура приставки. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogPanel(460.dp) {
            Text(title, color = Palette.Ink, fontSize = ts(19f), fontWeight = FontWeight.SemiBold)
            var text by remember { mutableStateOf(initial) }
            val field = remember { FocusRequester() }
            var fieldFocused by remember { mutableStateOf(false) }
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = if (fieldFocused) Palette.OnFocus else Palette.Ink, fontSize = ts(18f), fontFamily = AppFont),
                cursorBrush = SolidColor(if (fieldFocused) Palette.OnFocus else Palette.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank()) {
                        onDismiss()
                        onDone(text.trim())
                    }
                }),
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .focusRequester(field)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .background(if (fieldFocused) Palette.Focus else Palette.Fill, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            MenuButton(
                text = stringResource(R.string.done),
                onClick = {
                    if (text.isNotBlank()) {
                        onDismiss()
                        onDone(text.trim())
                    }
                },
                modifier = Modifier.padding(top = 10.dp),
            )
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        }
    }
}

@Composable
private fun DialogPanel(width: Dp, content: @Composable () -> Unit) {
    // Окно «всплывает»: из чуть меньшего и прозрачного — плавно, как на Apple TV.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 200f)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .width(width)
                .heightIn(max = 480.dp)
                .background(Palette.Panel, RoundedCornerShape(24.dp))
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) { content() }
    }
}

/** Однострочный текст с многоточием. */
@Composable
fun Line(text: String, size: Float, color: Color = Palette.Ink, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = ts(size), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Откуда пришёл фокус: последнее нажатие стрелки (−1, 0, 1 по каждой оси). По нему плитка,
 * получившая фокус, качнётся в сторону движения — как на Apple TV.
 */
object NavDirection {
    var dx = 0
    var dy = 0
}

/**
 * Прокрутка, при которой выбранный элемент встаёт на одно и то же место ([fraction] от
 * свободного пространства), а двигается содержимое — фокус не «убегает» к краю экрана.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class PivotScroll(private val fraction: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    /** Прокрутка следует за фокусом плавно, а не рывком. */
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 160f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = (containerSize - size).coerceAtLeast(0f) * fraction
        return offset - target
    }
}
