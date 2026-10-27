package ru.iptvsimple.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
                        "Ряд пуст. Удерживайте ОК на плитке в «Остальных приложениях» и выберите «Добавить в ряд».",
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
                RowTitle(if (rows.isEmpty()) "Все приложения" else "Остальные приложения")
                // Сеткой: столько плиток в строке, сколько помещается по ширине.
                val perLine = ((960f - 88f + 14f) / (config.tileSize.width + 14f)).toInt().coerceAtLeast(1)
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
                Text("Загружаю приложения…", color = Palette.InkSoft, fontSize = ts(14f), modifier = Modifier.padding(top = 20.dp))
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
                "Стрелками — переставить, ОК — готово",
                color = Palette.Moving,
                fontSize = ts(15f),
                fontWeight = FontWeight.SemiBold,
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
        size = 15f,
        color = Palette.InkSoft,
        weight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun TileRow(scrollable: Boolean = true, dock: Boolean = false, content: @Composable () -> Unit) {
    Row(
        // Отступы, чтобы увеличенная в фокусе плитка не обрезалась краем ряда.
        // Первый ряд — «док» на матовой подложке, как на Apple TV.
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (dock) {
                    Modifier
                        .padding(top = 8.dp, bottom = 6.dp)
                        .background(Color(0x26FFFFFF), RoundedCornerShape(22.dp))
                        .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                } else Modifier
            )
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
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
    Column(Modifier.width(width)) {
        FocusBox(
            onClick = onClick,
            onLongClick = onMenu,
            onFocusChange = { focused = it; if (it) onFocused() },
            modifier = Modifier
                .size(width, height)
                .focusRequester(requester),
        ) { hasFocus ->
            // Выбранная плитка приподнимается: крупнее, с тенью — как на Apple TV.
            val scale by animateFloatAsState(if (hasFocus) 1.1f else 1f, label = "tile")
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationY = if (hasFocus) -3.dp.toPx() else 0f
                    }
                    .shadow(if (hasFocus) 18.dp else 0.dp, shape)
                    .clip(shape)
                    .background(Palette.Card)
                    .border(
                        width = if (moving) 3.dp else if (hasFocus) 2.dp else 0.dp,
                        color = if (moving) Palette.Moving else if (hasFocus) Color(0xCCFFFFFF) else Color.Transparent,
                        shape = shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    app.banner != null -> Image(app.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else -> IconTile(app)
                }
                if (moving) {
                    Text("◀  ▲▼  ▶", color = Palette.Moving, fontSize = ts(14f), fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        val showLabel = config.labelMode == LabelMode.Always || focused
        Text(
            app.label,
            color = if (focused) Palette.Ink else Palette.InkSoft,
            fontSize = ts(12f),
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp)
                .graphicsLayer { alpha = if (showLabel) 1f else 0f },
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
            fontSize = ts(11f),
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
        add(MenuItem("Открыть") { onLaunch(pkg) })
        if (r >= 0) {
            add(MenuItem("Переставить стрелками") { onMove(pkg) })
            rows.forEachIndexed { i, row ->
                if (i != r) add(MenuItem("Перенести в ряд «${row.title}»") { onConfig(config.putApp(pkg, row.id)); onFocus(pkg) })
            }
            add(MenuItem("Убрать с главного экрана") { onConfig(config.removeApp(pkg)) })
        } else {
            rows.forEach { row ->
                add(MenuItem("Добавить в ряд «${row.title}»") { onConfig(config.putApp(pkg, row.id)); onFocus(pkg) })
            }
            add(MenuItem("Создать новый ряд с этим приложением") { newRow = true })
            add(MenuItem("Скрыть приложение") { onConfig(config.copy(hidden = config.hidden + pkg)) })
        }
        if (config.autostart == pkg) {
            add(MenuItem("Не открывать при включении приставки") { onConfig(config.copy(autostart = null)) })
        } else {
            add(MenuItem("Открывать при включении приставки") { onConfig(config.copy(autostart = pkg)) })
        }
        add(MenuItem("Отмена") { onFocus(pkg) })
    }
    MenuDialog(
        title = apps[pkg]?.label ?: pkg,
        subtitle = if (r >= 0) "Ряд «${rows[r].title}»" else "Остальные приложения",
        items = items,
        onDismiss = { if (!newRow) onDismiss() },
    )
}

/** Готовые названия ряда и ввод своего. */
@Composable
fun RowNameDialog(onDone: (String) -> Unit, onDismiss: () -> Unit, title: String = "Название ряда", initial: String = "") {
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        TextInputDialog(title = title, initial = initial, onDone = onDone, onDismiss = onDismiss)
    } else {
        val presets = listOf("Избранное", "Телевидение", "Кино и сериалы", "Видео", "Музыка и радио", "Детям", "Игры", "Прочее")
        MenuDialog(
            title = title,
            items = presets.map { MenuItem(it) { onDone(it) } } + MenuItem("Ввести своё название…") { typing = true },
            onDismiss = { if (!typing) onDismiss() },
        )
    }
}
