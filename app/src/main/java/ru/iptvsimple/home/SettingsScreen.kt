package ru.iptvsimple.home

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
    data object Boot : SettingsDialog
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
    updateStatus: UpdateStatus,
    onConfig: (HomeConfig) -> Unit,
    onCheckUpdates: () -> Unit,
    onSystemSettings: () -> Unit,
) {
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bootLogo by remember { mutableStateOf(BootScreen.current(context)) }
    var bootBusy by remember { mutableStateOf(false) }
    var bootStatus by remember { mutableStateOf<BootScreen.Result?>(null) }
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
                stringResource(R.string.settings),
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
            Section(stringResource(R.string.section_device), first = true)
            MenuButton(
                stringResource(R.string.device_settings),
                value = stringResource(R.string.device_settings_value),
                onClick = onSystemSettings,
                modifier = Modifier.focusRequester(first),
            )
            MenuButton(
                stringResource(R.string.boot_screen),
                value = if (bootBusy) stringResource(R.string.boot_applying) else stringResource(bootLogo.title),
                onClick = { if (!bootBusy) dialog = SettingsDialog.Boot },
            )
            bootStatus?.let { result ->
                Note(
                    when (result) {
                        BootScreen.Result.Done -> stringResource(R.string.boot_done)
                        BootScreen.Result.DoneAnimationOnly -> stringResource(R.string.boot_done_animation)
                        BootScreen.Result.NoRoot -> stringResource(R.string.boot_no_root)
                        BootScreen.Result.NoCustomImage -> stringResource(R.string.boot_no_image, BootScreen.CUSTOM_PATH)
                        is BootScreen.Result.Failed -> stringResource(R.string.boot_failed, result.message)
                    },
                )
            }

            Section(stringResource(R.string.section_appearance))
            Choice(stringResource(R.string.style), config.style, HomeStyle.entries, { stringResource(it.title) }) { onConfig(config.copy(style = it)) }
            Choice(stringResource(R.string.tile_size), config.tileSize, TileSize.entries, { stringResource(it.title) }) { onConfig(config.copy(tileSize = it)) }
            Choice(stringResource(R.string.text_size), config.textSize, TextSize.entries, { stringResource(it.title) }) { onConfig(config.copy(textSize = it)) }
            Choice(stringResource(R.string.app_names), config.labelMode, LabelMode.entries, { stringResource(it.title) }) { onConfig(config.copy(labelMode = it)) }
            if (config.style == HomeStyle.Modern) Choice(stringResource(R.string.background), config.backdrop, Backdrop.entries, { stringResource(it.title) }) { onConfig(config.copy(backdrop = it)) }
            if (config.style == HomeStyle.Modern && photoCount > 0 &&
                (config.backdrop == Backdrop.PhotoFixed || config.backdrop == Backdrop.PhotosRotating)
            ) {
                MenuButton(
                    stringResource(R.string.photos),
                    value = if (config.backdrop == Backdrop.PhotoFixed) {
                        stringResource(R.string.n_of_m, config.photoIndex.coerceIn(0, photoCount - 1) + 1, photoCount)
                    } else {
                        stringResource(R.string.photos_all)
                    },
                    onClick = { dialog = SettingsDialog.Gallery },
                )
            }
            Choice(stringResource(R.string.clock), config.showClock, listOf(true, false), { stringResource(if (it) R.string.show else R.string.hide) }) {
                onConfig(config.copy(showClock = it))
            }

            Section(stringResource(R.string.section_rows))
            Note(stringResource(R.string.rows_note))
            config.rows.forEach { row ->
                val count = row.apps.count { it in apps }
                MenuButton(
                    stringResource(R.string.quoted, row.title),
                    value = appsCount(count),
                    onClick = { dialog = SettingsDialog.RowActions(row.id) },
                )
            }
            MenuButton("+ " + stringResource(R.string.add_row), onClick = { dialog = SettingsDialog.NewRow })
            Choice(stringResource(R.string.other_apps_quoted), config.showOthers, listOf(true, false), { stringResource(if (it) R.string.show else R.string.hide) }) {
                onConfig(config.copy(showOthers = it))
            }

            Section(stringResource(R.string.section_apps))
            MenuButton(
                stringResource(R.string.open_at_startup),
                value = config.autostart?.let(::label) ?: stringResource(R.string.nothing_home_only),
                onClick = { dialog = SettingsDialog.Autostart },
            )
            MenuButton(
                stringResource(R.string.hidden_apps),
                value = if (config.hidden.isEmpty()) stringResource(R.string.none) else appsCount(config.hidden.size),
                onClick = { if (config.hidden.isNotEmpty()) dialog = SettingsDialog.Hidden },
            )

            Section(stringResource(R.string.section_about))
            Choice(stringResource(R.string.updates), config.checkUpdates, listOf(true, false), { stringResource(if (it) R.string.on else R.string.off) }) {
                onConfig(config.copy(checkUpdates = it))
            }
            MenuButton(
                stringResource(R.string.update_check_now),
                value = when (updateStatus) {
                    UpdateStatus.Idle -> null
                    UpdateStatus.Checking -> stringResource(R.string.update_checking)
                    UpdateStatus.Latest -> stringResource(R.string.update_latest)
                    UpdateStatus.NoConnection -> stringResource(R.string.update_no_connection)
                    is UpdateStatus.Available -> stringResource(R.string.update_available, updateStatus.version)
                },
                onClick = onCheckUpdates,
            )
            Note(stringResource(R.string.updates_note))
            Note(stringResource(R.string.about_note, versionName))
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
                    title = stringResource(R.string.row_named, row.title),
                    items = buildList {
                        add(MenuItem(stringResource(R.string.rename)) { dialog = SettingsDialog.Rename(row.id) })
                        if (index > 0) add(MenuItem(stringResource(R.string.move_up)) { onConfig(config.copy(rows = rows.swap(index, index - 1))) })
                        if (index < rows.lastIndex) add(MenuItem(stringResource(R.string.move_down)) { onConfig(config.copy(rows = rows.swap(index, index + 1))) })
                        add(MenuItem(stringResource(R.string.delete_row)) { onConfig(config.copy(rows = rows - row)) })
                        add(MenuItem(stringResource(R.string.cancel)) { })
                    },
                    onDismiss = { if (dialog == d) dialog = null },
                )
            }
        }
        is SettingsDialog.Rename -> {
            val row = config.rows.firstOrNull { it.id == d.rowId }
            RowNameDialog(
                title = stringResource(R.string.row_new_name),
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
            title = stringResource(R.string.open_at_startup),
            subtitle = stringResource(R.string.autostart_subtitle),
            items = listOf(MenuItem(stringResource(R.string.nothing_home_only)) { onConfig(config.copy(autostart = null)) }) +
                apps.values.sortedBy { it.label.lowercase() }.map { app ->
                    MenuItem(app.label) { onConfig(config.copy(autostart = app.pkg)) }
                },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Hidden -> MenuDialog(
            title = stringResource(R.string.hidden_apps),
            subtitle = stringResource(R.string.hidden_subtitle),
            items = listOf(MenuItem(stringResource(R.string.unhide_all)) { onConfig(config.copy(hidden = emptySet())) }) +
                config.hidden.sortedBy { label(it).lowercase() }.map { pkg ->
                    MenuItem(stringResource(R.string.unhide_app, label(pkg))) { onConfig(config.copy(hidden = config.hidden - pkg)) }
                },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Boot -> MenuDialog(
            title = stringResource(R.string.boot_screen),
            subtitle = stringResource(R.string.boot_subtitle, BootScreen.CUSTOM_PATH),
            items = BootLogo.entries.map { choice ->
                MenuItem(stringResource(choice.title)) {
                    bootBusy = true
                    bootStatus = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { BootScreen.apply(context, choice) }
                        if (result == BootScreen.Result.Done || result == BootScreen.Result.DoneAnimationOnly) bootLogo = choice
                        bootStatus = result
                        bootBusy = false
                    }
                }
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
private fun <T> Choice(title: String, value: T, options: List<T>, name: @Composable (T) -> String, onChange: (T) -> Unit) {
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

@Composable
private fun appsCount(n: Int): String = pluralStringResource(R.plurals.apps_count, n, n)

private fun <T> List<T>.swap(a: Int, b: Int): List<T> = toMutableList().also {
    val t = it[a]; it[a] = it[b]; it[b] = t
}
