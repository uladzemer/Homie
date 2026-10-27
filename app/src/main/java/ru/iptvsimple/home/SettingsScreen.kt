package ru.iptvsimple.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/** Какое окно открыто поверх настроек. */
private sealed interface SettingsDialog {
    data class RowActions(val rowId: Long) : SettingsDialog
    data class Rename(val rowId: Long) : SettingsDialog
    data object NewRow : SettingsDialog
    data object Autostart : SettingsDialog
    data object Hidden : SettingsDialog
    data object Gallery : SettingsDialog
}

/**
 * Настройки главного экрана в духе tvOS: слева крупный значок раздела, справа список.
 * Изменения применяются сразу: главный экран виден сквозь затемнение.
 * Значения с вариантами листаются кнопками ОК, ◀ и ▶.
 */
@Composable
fun SettingsScreen(
    config: HomeConfig,
    apps: Map<String, InstalledApp>,
    photoCount: Int,
    versionName: String,
    onConfig: (HomeConfig) -> Unit,
    onSystemSettings: () -> Unit,
) {
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun label(pkg: String) = apps[pkg]?.label ?: pkg

    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2000000)),
    ) {
        // Левая половина: значок раздела и заголовок, как в «Настройках» Apple TV.
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = Palette.InkSoft, modifier = Modifier.size(150.dp))
            Text(
                "Настройки",
                color = Palette.Ink,
                fontSize = ts(28f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text("Homie $versionName", color = Palette.InkMuted, fontSize = ts(13f), modifier = Modifier.padding(top = 4.dp))
        }
        Column(
            Modifier
                // Фокус не уходит из списка на плитки главного экрана под затемнением.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .width(500.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 6.dp, end = 44.dp, top = 40.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Приставка", first = true)
            MenuButton(
                "Настройки приставки",
                value = "Wi-Fi, звук, экран, дата и время",
                onClick = onSystemSettings,
                modifier = Modifier.focusRequester(first),
            )

            Section("Вид")
            Choice("Стиль", config.style, HomeStyle.entries, { it.title }) { onConfig(config.copy(style = it)) }
            Choice("Размер плиток", config.tileSize, TileSize.entries, { it.title }) { onConfig(config.copy(tileSize = it)) }
            Choice("Размер надписей", config.textSize, TextSize.entries, { it.title }) { onConfig(config.copy(textSize = it)) }
            Choice("Названия приложений", config.labelMode, LabelMode.entries, { it.title }) { onConfig(config.copy(labelMode = it)) }
            if (config.style == HomeStyle.Modern) Choice("Фон", config.backdrop, Backdrop.entries, { it.title }) { onConfig(config.copy(backdrop = it)) }
            if (config.style == HomeStyle.Modern && photoCount > 0 &&
                (config.backdrop == Backdrop.PhotoFixed || config.backdrop == Backdrop.PhotosRotating)
            ) {
                MenuButton(
                    "Фотографии",
                    value = if (config.backdrop == Backdrop.PhotoFixed) "${config.photoIndex.coerceIn(0, photoCount - 1) + 1} из $photoCount" else "Все по очереди",
                    onClick = { dialog = SettingsDialog.Gallery },
                )
            }
            Choice("Часы и дата", config.showClock, listOf(true, false), { if (it) "Показывать" else "Скрыть" }) {
                onConfig(config.copy(showClock = it))
            }

            Section("Ряды")
            Note("Чтобы переставить или перенести приложение, на главном экране удерживайте ОК на его плитке.")
            config.rows.forEach { row ->
                val count = row.apps.count { it in apps }
                MenuButton(
                    "«${row.title}»",
                    value = appsCount(count),
                    onClick = { dialog = SettingsDialog.RowActions(row.id) },
                )
            }
            MenuButton("+ Добавить ряд", onClick = { dialog = SettingsDialog.NewRow })
            Choice("«Остальные приложения»", config.showOthers, listOf(true, false), { if (it) "Показывать" else "Скрыть" }) {
                onConfig(config.copy(showOthers = it))
            }

            Section("Приложения")
            MenuButton(
                "При включении приставки открывать",
                value = config.autostart?.let(::label) ?: "Ничего, только главный экран",
                onClick = { dialog = SettingsDialog.Autostart },
            )
            MenuButton(
                "Скрытые приложения",
                value = if (config.hidden.isEmpty()) "Нет" else appsCount(config.hidden.size),
                onClick = { if (config.hidden.isNotEmpty()) dialog = SettingsDialog.Hidden },
            )

            Section("О программе")
            Note("Homie $versionName. Без рекламы и интернета. Шрифт Inter — SIL OFL. Фотографии — Wikimedia Commons, CC BY-SA.")
        }
    }

    when (val d = dialog) {
        null -> Unit
        is SettingsDialog.RowActions -> {
            val index = config.rows.indexOfFirst { it.id == d.rowId }
            val row = config.rows.getOrNull(index)
            if (row == null) {
                dialog = null
            } else {
                val rows = config.rows
                MenuDialog(
                    title = "Ряд «${row.title}»",
                    items = buildList {
                        add(MenuItem("Переименовать") { dialog = SettingsDialog.Rename(row.id) })
                        if (index > 0) add(MenuItem("Поднять выше") { onConfig(config.copy(rows = rows.swap(index, index - 1))) })
                        if (index < rows.lastIndex) add(MenuItem("Опустить ниже") { onConfig(config.copy(rows = rows.swap(index, index + 1))) })
                        add(MenuItem("Удалить ряд (приложения уйдут в «Остальные»)") { onConfig(config.copy(rows = rows - row)) })
                        add(MenuItem("Отмена") { })
                    },
                    onDismiss = { if (dialog == d) dialog = null },
                )
            }
        }
        is SettingsDialog.Rename -> {
            val row = config.rows.firstOrNull { it.id == d.rowId }
            RowNameDialog(
                title = "Новое название ряда",
                initial = row?.title.orEmpty(),
                onDone = { title -> onConfig(config.updateRow(d.rowId) { it.copy(title = title) }) },
                onDismiss = { dialog = null },
            )
        }
        SettingsDialog.NewRow -> RowNameDialog(
            onDone = { title -> onConfig(config.copy(rows = config.rows + HomeRow(config.nextRowId(), title, emptyList()))) },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Autostart -> MenuDialog(
            title = "При включении приставки открывать",
            subtitle = "Главный экран останется на кнопке «Домой»",
            items = listOf(MenuItem("Ничего, только главный экран") { onConfig(config.copy(autostart = null)) }) +
                apps.values.sortedBy { it.label.lowercase() }.map { app ->
                    MenuItem(app.label) { onConfig(config.copy(autostart = app.pkg)) }
                },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Hidden -> MenuDialog(
            title = "Скрытые приложения",
            subtitle = "Выберите, какое вернуть в «Остальные приложения»",
            items = listOf(MenuItem("Вернуть все") { onConfig(config.copy(hidden = emptySet())) }) +
                config.hidden.sortedBy { label(it).lowercase() }.map { pkg ->
                    MenuItem("Вернуть «${label(pkg)}»") { onConfig(config.copy(hidden = config.hidden - pkg)) }
                },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Gallery -> WallpaperGallery(
            selected = config.photoIndex.takeIf { config.backdrop == Backdrop.PhotoFixed },
            onPick = { onConfig(config.copy(backdrop = Backdrop.PhotoFixed, photoIndex = it)) },
            onDismiss = { dialog = null },
        )
    }
}

/** Заголовок группы — серым, как в списках tvOS. */
@Composable
private fun Section(title: String, first: Boolean = false) {
    Text(
        title,
        color = Palette.InkMuted,
        fontSize = ts(13f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = if (first) 0.dp else 18.dp, start = 18.dp, bottom = 2.dp),
    )
}

/** Пояснение под группой. */
@Composable
private fun Note(text: String) {
    Text(text, color = Palette.InkMuted, fontSize = ts(12.5f), modifier = Modifier.padding(horizontal = 18.dp))
}

/** Настройка с вариантами: ОК и ▶ — следующий, ◀ — предыдущий. */
@Composable
private fun <T> Choice(title: String, value: T, options: List<T>, name: (T) -> String, onChange: (T) -> Unit) {
    val index = options.indexOf(value).coerceAtLeast(0)
    fun step(by: Int) = onChange(options[(index + by + options.size) % options.size])
    MenuButton(
        title,
        value = name(value),
        onClick = { step(1) },
        onKey = { e: KeyEvent ->
            when (e.nativeKeyEvent.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (e.type == KeyEventType.KeyDown) step(-1); true }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.type == KeyEventType.KeyDown) step(1); true }
                else -> false
            }
        },
    )
}

private fun appsCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "приложений"
        n % 10 == 1 -> "приложение"
        n % 10 in 2..4 -> "приложения"
        else -> "приложений"
    }
    return "$n $word"
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> = toMutableList().also {
    val t = it[a]; it[a] = it[b]; it[b] = t
}
