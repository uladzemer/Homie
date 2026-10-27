@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ru.iptvsimple.home

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val VerticalPivot = PivotScroll(0.06f)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val HorizontalPivot = PivotScroll(0.12f)

/** Что сейчас переставляют пультом. */
private data class Moving(val pkg: String)

/**
 * Главный экран: часы, кнопка настроек, ряды пользователя и «Остальные приложения».
 * [focusHome] меняется при каждом нажатии «Домой» — фокус возвращается на первую плитку.
 */
@Composable
fun HomeScreen(
    config: HomeConfig,
    apps: Map<String, InstalledApp>,
    focusHome: Int,
    onConfig: (HomeConfig) -> Unit,
    onLaunch: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDeepFocus: (Boolean) -> Unit = {},
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(pkg: String) = requesters.getOrPut(pkg) { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }

    var moving by remember { mutableStateOf<Moving?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }

    val rows = config.rows
    val others = apps.values
        .filter { it.pkg !in config.placed && it.pkg !in config.hidden }
        .sortedBy { it.label.lowercase() }
    val firstTile = rows.firstNotNullOfOrNull { row -> row.apps.firstOrNull { it in apps } }
        ?: others.takeIf { config.showOthers }?.firstOrNull()?.pkg

    // Фокус на первую плитку при запуске и по кнопке «Домой».
    LaunchedEffect(focusHome, apps.isNotEmpty()) {
        if (apps.isEmpty()) return@LaunchedEffect
        moving = null
        withFrameNanos { }
        runCatching { (firstTile?.let(::requester) ?: settingsRequester).requestFocus() }
    }
    // После перестановки фокус следует за плиткой.
    LaunchedEffect(pendingFocus, config) {
        val pkg = pendingFocus ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(pkg).requestFocus() }
        pendingFocus = null
    }

    fun moveBy(pkg: String, dx: Int, dy: Int) {
        onConfig(config.moveApp(pkg, dx, dy) ?: return)
        pendingFocus = pkg
    }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val m = moving ?: return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                if (e.type == KeyEventType.KeyDown) {
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> moveBy(m.pkg, -1, 0)
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(m.pkg, 1, 0)
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> moveBy(m.pkg, 0, -1)
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> moveBy(m.pkg, 0, 1)
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BACK,
                        AndroidKeyEvent.KEYCODE_BUTTON_A, AndroidKeyEvent.KEYCODE_MENU -> moving = null
                    }
                }
                true
            }
            .padding(horizontal = 44.dp),
    ) {
        Header(config, moving != null, settingsRequester, onOpenSettings)
        // Выбранный ряд встаёт почти к верху, а ряды прокручиваются под ним.
        CompositionLocalProvider(LocalBringIntoViewSpec provides VerticalPivot) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = 40.dp),
        ) {
            rows.forEachIndexed { rowIndex, row ->
                val shown = row.apps.mapNotNull { apps[it] }
                val dock = rowIndex == 0
                if (!dock) RowTitle(row.title)
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.row_empty),
                        color = Palette.InkMuted,
                        fontSize = ts(12f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                } else {
                    TileRow(dock = dock) {
                        shown.forEach { app ->
                            key(app.pkg) {
                                AppTile(
                                    app = app,
                                    config = config,
                                    moving = moving?.pkg == app.pkg,
                                    requester = requester(app.pkg),
                                    onClick = { onLaunch(app.pkg) },
                                    onMenu = { menuFor = app.pkg },
                                    onFocused = { onDeepFocus(!dock) },
                                )
                            }
                        }
                    }
                }
            }
            if (config.showOthers && others.isNotEmpty()) {
                RowTitle(stringResource(if (rows.isEmpty()) R.string.all_apps else R.string.other_apps))
                // Сеткой: столько плиток в строке, сколько помещается по ширине.
                val perLine = ((960f - 88f - 28f + 18f) / (config.tileSize.width + 18f)).toInt().coerceAtLeast(1)
                others.chunked(perLine).forEach { line ->
                    TileRow(scrollable = false) {
                        line.forEach { app ->
                            key(app.pkg) {
                                AppTile(
                                    app = app,
                                    config = config,
                                    moving = false,
                                    requester = requester(app.pkg),
                                    onClick = { onLaunch(app.pkg) },
                                    onMenu = { menuFor = app.pkg },
                                    onFocused = { onDeepFocus(true) },
                                )
                            }
                        }
                    }
                }
            }
            if (apps.isEmpty()) {
                Text(stringResource(R.string.loading_apps), color = Palette.InkSoft, fontSize = ts(14f), modifier = Modifier.padding(top = 20.dp))
            }
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
            onMove = { moving = Moving(it); pendingFocus = it },
            onFocus = { pendingFocus = it },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun Header(config: HomeConfig, moving: Boolean, settingsRequester: FocusRequester, onOpenSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 10.dp),
    ) {
        if (config.showClock) HomeClock()
        if (moving) {
            Text(
                stringResource(R.string.moving_hint),
                color = Palette.Ink,
                fontSize = ts(15f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        StatusBar(settingsRequester, onOpenSettings, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
fun HomeClock() {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
        }
    }
    val locale = Locale.getDefault()
    Column {
        Text(
            SimpleDateFormat("H:mm", locale).format(now.time),
            color = Palette.Ink,
            fontSize = ts(40f),
            fontWeight = FontWeight.Light,
        )
        Text(
            SimpleDateFormat("EEEE, d MMMM", locale).format(now.time).replaceFirstChar { it.titlecase(locale) },
            color = Palette.InkSoft,
            fontSize = ts(14f),
        )
    }
}

@Composable
private fun RowTitle(title: String) {
    Line(
        title,
        size = 17f,
        color = Palette.InkSoft,
        weight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun TileRow(scrollable: Boolean = true, dock: Boolean = false, content: @Composable () -> Unit) {
    // По ряду выбранная плитка держится у левого края, листается сам ряд.
    CompositionLocalProvider(LocalBringIntoViewSpec provides HorizontalPivot) {
    Row(
        // Отступы, чтобы увеличенная в фокусе плитка не обрезалась краем ряда.
        // Первый ряд — «док» на матовой подложке без рамки, как на Apple TV.
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (dock) {
                    Modifier
                        .padding(top = 8.dp, bottom = 6.dp)
                        .background(Color(0x1FFFFFFF), RoundedCornerShape(26.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                } else Modifier
            )
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            // Запас по краям: плитка в фокусе крупнее на 12 % и с тенью.
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) { content() }
    }
}

@Composable
private fun AppTile(
    app: InstalledApp,
    config: HomeConfig,
    moving: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocused: () -> Unit = {},
) {
    val width = config.tileSize.width.dp
    val height = width * 9f / 16f
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val motion = rememberFocusMotion(focused, pressed)
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(width).wiggle(moving)) {
        FocusBox(
            onClick = onClick,
            onLongClick = onMenu,
            onFocusChange = { focused = it; if (it) onFocused() },
            onPressChange = { pressed = it },
            modifier = Modifier
                .size(width, height)
                .focusRequester(requester),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusLift(motion, shape)
                    .clip(shape)
                    .background(Palette.Card)
                    .focusGloss(motion),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    app.banner != null -> Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else -> IconTile(app)
                }
                if (moving) {
                    Text("‹  ↕  ›", color = Palette.Ink, fontSize = ts(13f), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .background(Palette.Pill, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
        }
        // Название выплывает из-под плитки вместе с подъёмом.
        Text(
            app.label,
            color = Palette.Ink,
            fontSize = ts(12.5f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .graphicsLayer {
                    val lift = motion.lift.value.coerceIn(0f, 1f)
                    alpha = if (config.labelMode == LabelMode.Always) 0.6f + 0.4f * lift else lift
                    translationY = (1f - lift) * -4.dp.toPx() + lift * 4.dp.toPx()
                },
        )
        Spacer(Modifier.height(2.dp))
    }
}

/** Плитка приложения без баннера: значок и название. */
@Composable
private fun IconTile(app: InstalledApp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(8.dp)) {
        app.icon?.let { Image(it, null, Modifier.size(40.dp)) }
        Text(
            app.label,
            color = Palette.Ink,
            fontSize = ts(11.5f),
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Меню плитки (долгое ОК или «Меню»): открыть, переставить, перенести, убрать, скрыть, автозапуск.
 * Общее для обычного вида и карусели.
 */
@Composable
fun TileMenu(
    pkg: String,
    config: HomeConfig,
    apps: Map<String, InstalledApp>,
    onConfig: (HomeConfig) -> Unit,
    onLaunch: (String) -> Unit,
    onMove: (String) -> Unit,
    onFocus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newRow by remember { mutableStateOf(false) }
    val rows = config.rows
    val r = rows.indexOfFirst { pkg in it.apps }
    if (newRow) {
        RowNameDialog(
            onDone = { title ->
                val id = config.nextRowId()
                onConfig(config.copy(rows = rows + HomeRow(id, title, emptyList())).putApp(pkg, id))
                onFocus(pkg)
            },
            onDismiss = onDismiss,
        )
        return
    }
    val items = buildList {
        add(MenuItem(stringResource(R.string.open)) { onLaunch(pkg) })
        if (r >= 0) {
            add(MenuItem(stringResource(R.string.move_with_arrows)) { onMove(pkg) })
            rows.forEachIndexed { i, row ->
                if (i != r) add(MenuItem(stringResource(R.string.move_to_row, row.title)) { onConfig(config.putApp(pkg, row.id)); onFocus(pkg) })
            }
            add(MenuItem(stringResource(R.string.remove_from_home)) { onConfig(config.removeApp(pkg)) })
        } else {
            rows.forEach { row ->
                add(MenuItem(stringResource(R.string.add_to_row, row.title)) { onConfig(config.putApp(pkg, row.id)); onFocus(pkg) })
            }
            add(MenuItem(stringResource(R.string.new_row_with_app)) { newRow = true })
            add(MenuItem(stringResource(R.string.hide_app)) { onConfig(config.copy(hidden = config.hidden + pkg)) })
        }
        if (config.autostart == pkg) {
            add(MenuItem(stringResource(R.string.autostart_off)) { onConfig(config.copy(autostart = null)) })
        } else {
            add(MenuItem(stringResource(R.string.autostart_on)) { onConfig(config.copy(autostart = pkg)) })
        }
        add(MenuItem(stringResource(R.string.cancel)) { onFocus(pkg) })
    }
    MenuDialog(
        title = apps[pkg]?.label ?: pkg,
        subtitle = if (r >= 0) stringResource(R.string.row_named, rows[r].title) else stringResource(R.string.other_apps),
        items = items,
        onDismiss = { if (!newRow) onDismiss() },
    )
}

/** Готовые названия ряда и ввод своего. */
@Composable
fun RowNameDialog(onDone: (String) -> Unit, onDismiss: () -> Unit, title: String = stringResource(R.string.row_name), initial: String = "") {
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        TextInputDialog(title = title, initial = initial, onDone = onDone, onDismiss = onDismiss)
    } else {
        val presets = stringArrayResource(R.array.row_presets).toList()
        MenuDialog(
            title = title,
            items = presets.map { MenuItem(it) { onDone(it) } } + MenuItem(stringResource(R.string.enter_own_name)) { typing = true },
            onDismiss = { if (!typing) onDismiss() },
        )
    }
}
