package ru.iptvsimple.home

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.SignalWifi0Bar
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.NetworkWifi1Bar
import androidx.compose.material.icons.rounded.NetworkWifi2Bar
import androidx.compose.material.icons.rounded.NetworkWifi3Bar
import androidx.compose.material.icons.rounded.SignalWifi4Bar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Состояние сети для значка. */
private sealed interface NetState {
    data object None : NetState
    data object Cable : NetState
    data class Wifi(val level: Int) : NetState
}

private fun readNet(context: Context): NetState {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetState.None
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetState.None
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState.Cable
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val level = runCatching { WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 5) }.getOrDefault(4)
            NetState.Wifi(level)
        }
        // VPN и прочее поверх кабеля или Wi-Fi: считаем, что сеть есть.
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetState.Cable
        else -> NetState.None
    }
}

/** Следит за сетью: обновляется при смене подключения и раз в 30 секунд (сила сигнала Wi-Fi). */
@Composable
private fun rememberNetState(): NetState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readNet(context)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state = readNet(context) }
            override fun onLost(network: Network) { state = readNet(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { state = readNet(context) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            state = readNet(context)
        }
    }
    return state
}

/**
 * Мини-кнопки справа вверху: сеть (значок показывает Wi-Fi, кабель или «нет сети»),
 * питание и настройки. Подпись появляется у выбранной кнопки.
 */
@Composable
fun StatusBar(settingsRequester: FocusRequester, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val net = rememberNetState()
    var powerMenu by remember { mutableStateOf(false) }

    val (netIcon, netText) = when (net) {
        NetState.None -> Icons.Rounded.SignalWifiOff to stringResource(R.string.net_none)
        NetState.Cable -> Icons.Rounded.Lan to stringResource(R.string.net_cable)
        is NetState.Wifi -> when (net.level) {
            0 -> Icons.Rounded.SignalWifi0Bar
            1 -> Icons.Rounded.NetworkWifi1Bar
            2 -> Icons.Rounded.NetworkWifi2Bar
            3 -> Icons.Rounded.NetworkWifi3Bar
            else -> Icons.Rounded.SignalWifi4Bar
        } to stringResource(R.string.net_wifi)
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(netIcon, netText, alert = net == NetState.None, onClick = {
            val action = if (net is NetState.Wifi || net == NetState.None) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_SETTINGS
            launchSettings(context, action)
        })
        RoundButton(Icons.Rounded.PowerSettingsNew, stringResource(R.string.power), onClick = { powerMenu = true })
        RoundButton(
            Icons.Rounded.Settings,
            stringResource(R.string.settings),
            onClick = onOpenSettings,
            modifier = Modifier.focusRequester(settingsRequester),
        )
    }

    if (powerMenu) PowerMenu(onDismiss = { powerMenu = false })
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    FocusBox(onClick = onClick, modifier = modifier, onPressChange = { pressed = it }) { focused ->
        val motion = rememberFocusMotion(focused, pressed)
        val (bg, focusInk) = buttonColors(focused, Palette.Pill)
        val ink = if (alert && !focused) Palette.Red else focusInk
        val shape = RoundedCornerShape(50)
        // Круглая кнопка; в фокусе плавно раскрывается в «таблетку» с подписью.
        Row(
            Modifier
                .focusLift(motion, shape, maxScale = 1.1f)
                .background(bg, shape)
                .animateContentSize(liftSpring())
                .padding(horizontal = if (focused) 14.dp else 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = text, tint = ink, modifier = Modifier.size(18.dp))
            if (focused) {
                Text(
                    text,
                    color = ink,
                    fontSize = ts(13f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Меню питания. Без включённой службы питания объясняет, как её включить. */
@Composable
private fun PowerMenu(onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (PowerService.isAvailable(context)) {
        MenuDialog(
            title = stringResource(R.string.power),
            items = buildList {
                if (PowerService.canSleep) add(MenuItem(stringResource(R.string.sleep)) { PowerService.sleep(context) })
                add(MenuItem(stringResource(R.string.restart_or_power_off)) { PowerService.powerDialog(context) })
                add(MenuItem(stringResource(R.string.cancel)) { })
            },
            onDismiss = onDismiss,
        )
    } else {
        MenuDialog(
            title = stringResource(R.string.power),
            subtitle = stringResource(R.string.power_setup),
            items = listOf(
                MenuItem(stringResource(R.string.open_accessibility)) { launchSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                MenuItem(stringResource(R.string.cancel)) { },
            ),
            onDismiss = onDismiss,
        )
    }
}

fun launchSettings(context: Context, action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
