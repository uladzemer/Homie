package ru.iptvsimple.home

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Галерея фотографий для фона — сетка миниатюр во весь экран, как выбор заставки на Apple TV.
 * ОК — сделать фоном и закрыть, «Назад» — закрыть.
 */
@Composable
fun WallpaperGallery(selected: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val photos = remember { Wallpapers.names(context) }
    val focus = remember { FocusRequester() }
    var current by remember { mutableStateOf(selected ?: 0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Palette.Window)) {
            // Выбранная миниатюра крупно на фоне — сразу видно, как будет выглядеть.
            val preview by produceState<ImageBitmap?>(null, current) {
                value = withContext(Dispatchers.IO) { photos.getOrNull(current)?.let { Wallpapers.thumb(context, it, 2) } }
            }
            preview?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 60.dp, vertical = 36.dp),
            ) {
                Text(stringResource(R.string.photos), color = Palette.Ink, fontSize = ts(28f), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.gallery_hint), color = Palette.InkSoft, fontSize = ts(13f), modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                photos.indices.chunked(4).forEach { line ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        line.forEach { i ->
                            PhotoTile(
                                name = photos[i],
                                chosen = i == selected,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (i == (selected ?: 0)) Modifier.focusRequester(focus) else Modifier),
                                onFocus = { current = i },
                                onClick = {
                                    onPick(i)
                                    onDismiss()
                                },
                            )
                        }
                        repeat(4 - line.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

@Composable
private fun PhotoTile(name: String, chosen: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { Wallpapers.thumb(context, name, 8) }
    }
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val motion = rememberFocusMotion(focused, pressed)
    val shape = RoundedCornerShape(12.dp)
    FocusBox(
        onClick = onClick,
        onFocusChange = { focused = it; if (it) onFocus() },
        onPressChange = { pressed = it },
        modifier = modifier.aspectRatio(16f / 9f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .focusLift(motion, shape)
                .clip(shape)
                .background(Palette.Card)
                .focusGloss(motion),
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (chosen) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = Palette.OnFocus,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(Palette.Focus, CircleShape)
                        .padding(3.dp),
                )
            }
        }
    }
}
