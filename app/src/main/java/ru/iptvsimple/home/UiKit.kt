package ru.iptvsimple.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

object Palette {
    val Window = Color(0xFF0E1621)
    val Ink = Color(0xFFFFFFFF)
    val InkSoft = Color(0xCCFFFFFF)
    val InkMuted = Color(0x99FFFFFF)
    val Card = Color(0xFF243142)
    val Panel = Color(0xF01A2533)
    val Pill = Color(0x66000000)
    val Focus = Color(0xFFFFFFFF)
    val OnFocus = Color(0xFF0E1621)
    val Moving = Color(0xFFFFC83D)
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
    onKey: ((KeyEvent) -> Boolean)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
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

/** Кнопка-«таблетка»: светлая в фокусе. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Float = 13f,
) {
    FocusBox(onClick = onClick, modifier = modifier) { focused ->
        Box(
            Modifier
                .background(if (focused) Palette.Focus else Palette.Pill, RoundedCornerShape(50))
                .border(1.dp, if (focused) Palette.Focus else Color(0x33FFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 7.dp),
        ) {
            Text(
                text,
                color = if (focused) Palette.OnFocus else Palette.Ink,
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
    FocusBox(onClick = onClick, modifier = modifier.fillMaxWidth(), onKey = onKey) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(if (focused) Palette.Focus else Color(0x14FFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val ink = if (focused) Palette.OnFocus else Palette.Ink
            Text(text, color = ink, fontSize = ts(15f), fontWeight = FontWeight.Medium)
            if (value != null) {
                Text(
                    if (focused && onKey != null) "◀  $value  ▶" else value,
                    color = if (focused) Palette.OnFocus.copy(alpha = 0.75f) else Palette.InkSoft,
                    fontSize = ts(13f),
                )
            }
            if (hint != null && focused) {
                Text(hint, color = Palette.OnFocus.copy(alpha = 0.6f), fontSize = ts(11f))
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
            Text(title, color = Palette.Ink, fontSize = ts(18f), fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = Palette.InkSoft, fontSize = ts(12f), modifier = Modifier.padding(top = 4.dp))
            }
            val first = remember { FocusRequester() }
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .verticalScroll(rememberScrollState()),
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
            Text(title, color = Palette.Ink, fontSize = ts(18f), fontWeight = FontWeight.SemiBold)
            var text by remember { mutableStateOf(initial) }
            val field = remember { FocusRequester() }
            var fieldFocused by remember { mutableStateOf(false) }
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Ink, fontSize = ts(18f)),
                cursorBrush = SolidColor(Palette.Ink),
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
                    .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                    .border(2.dp, if (fieldFocused) Palette.Focus else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            MenuButton(
                text = "Готово",
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(width)
                .heightIn(max = 480.dp)
                .background(Palette.Panel, RoundedCornerShape(18.dp))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp))
                .padding(22.dp),
        ) { content() }
    }
}

/** Однострочный текст с многоточием. */
@Composable
fun Line(text: String, size: Float, color: Color = Palette.Ink, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = ts(size), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
