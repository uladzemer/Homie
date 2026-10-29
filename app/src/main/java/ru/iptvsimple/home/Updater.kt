package ru.iptvsimple.home

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Обновление из релизов GitHub: раз в сутки смотрим последний релиз, и если он новее —
 * предлагаем поставить. «Позже» — не спрашиваем неделю. С root ставим молча через pm,
 * без root — через системный установщик (он сам спросит подтверждение).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/uladzemer/Homie/releases/latest"
    private const val CHECK_EVERY = 24 * 3600_000L
    private const val SNOOZE = 7 * 24 * 3600_000L
    private val activeFiles = HashSet<File>()

    class Release(val version: String, val apkUrl: String)

    /** Ошибка от системного установщика; приходит позже, в [UpdateReceiver]. */
    var installError by mutableStateOf<String?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Пора ли спросить само: не отладочная сборка, не отложено, с прошлой проверки прошли сутки. */
    fun due(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return false
        val now = System.currentTimeMillis()
        val p = prefs(context)
        return now >= p.getLong(KEY_SNOOZE, 0) && now - p.getLong(KEY_CHECKED, 0) >= CHECK_EVERY
    }

    fun snooze(context: Context) {
        prefs(context).edit().putLong(KEY_SNOOZE, System.currentTimeMillis() + SNOOZE).apply()
    }

    /** Последний релиз, если он новее установленного; null — обновлять нечего. Ошибка сети — исключение. */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        prefs(context).edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
        if (!isNewer(version, currentVersion(context))) return@withContext null
        Release(version, apk.getString("browser_download_url"))
    }

    /** Сравнение версий вида 0.10.2 по числам. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Скачивает APK в кэш; [onProgress] — доля от 0 до 1. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        var pending: File? = null
        try {
            return withContext(Dispatchers.IO) {
                coroutineScope {
                    val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    val file = synchronized(activeFiles) {
                        context.cacheDir.listFiles()?.filter {
                            it.name.startsWith("update") && (it.name.endsWith(".apk") || it.name.endsWith(".part")) && it !in activeFiles
                        }?.forEach { it.delete() }
                        File.createTempFile("update-", ".apk.part", context.cacheDir).also {
                            activeFiles += it
                            activeFiles += File(it.path.removeSuffix(".part"))
                        }
                    }
                    val completed = File(file.path.removeSuffix(".part"))
                    pending = completed
                    // Отмена разрывает соединение, даже если чтение ещё ждёт данные.
                    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { conn.disconnect() }
                    }
                    try {
                        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    coroutineContext.ensureActive()
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) onProgress(done.toFloat() / total)
                                }
                                check(total < 0 || done == total) { "incomplete APK" }
                            }
                        }
                        coroutineContext.ensureActive()
                        // Чужой или старый файл не ставим: проверяем пакет и номер версии.
                        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
                        val current = context.packageManager.getPackageInfo(context.packageName, 0)
                        @Suppress("DEPRECATION")
                        if (info?.packageName != context.packageName || info.versionCode <= current.versionCode) {
                            error("bad APK")
                        }
                        coroutineContext.ensureActive()
                        check(file.renameTo(completed)) { "rename failed" }
                        completed
                    } finally {
                        disconnect.cancel()
                        conn.disconnect()
                        file.delete()
                        synchronized(activeFiles) { activeFiles -= file }
                    }
                }
            }
        } catch (e: Throwable) {
            // withContext может отмениться уже после rename, при возврате на главный поток.
            pending?.let { file ->
                file.delete()
                synchronized(activeFiles) { activeFiles -= file }
            }
            throw e
        }
    }

    /** Ставит скачанный APK. После удачной установки система сама перезапустит главный экран. */
    suspend fun install(context: Context, file: File) {
        try {
            withContext(Dispatchers.IO) { installApk(context, file) }
        } finally {
            file.delete()
            synchronized(activeFiles) { activeFiles -= file }
        }
    }

    private suspend fun installApk(context: Context, file: File) {
        installError = null
        val root = Root.available()
        coroutineContext.ensureActive()
        if (root) {
            val script = """
                tmp=${'$'}(mktemp /data/local/tmp/homie-update.XXXXXXXX) || exit 1
                trap 'rm -f "${'$'}tmp"' EXIT
                trap 'exit 129' HUP
                trap 'exit 130' INT
                trap 'exit 143' TERM
                cp '${file.path}' "${'$'}tmp" && chmod 644 "${'$'}tmp" && pm install -r "${'$'}tmp"
                """
            coroutineContext.ensureActive()
            // Во время root-staging отмену не проверяем: ставится проверенный новый Homie с той же подписью.
            val out = Root.run(script)
            if (out.success && "Success" in out.output) return
        }
        coroutineContext.ensureActive()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("homie.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            coroutineContext.ensureActive()
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
                coroutineContext.ensureActive()
                session.commit(callback.intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private const val KEY_CHECKED = "update_checked"
    private const val KEY_SNOOZE = "update_snooze_until"
}

/** Ответ системного установщика: показать окно подтверждения или сообщить об ошибке. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updater.installError = it.message ?: it.javaClass.simpleName }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.installError = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
        }
    }
}

/** Итог ручной проверки — для строки в настройках. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object Latest : UpdateStatus
    data object NoConnection : UpdateStatus
    data class Available(val version: String) : UpdateStatus
}

private sealed interface UpdateStage {
    data object Prompt : UpdateStage
    data object Downloading : UpdateStage
    data object Installing : UpdateStage
    data class Failed(val message: String) : UpdateStage
}

/** Окно «Вышла новая версия»: обновить сейчас или напомнить через неделю. */
@Composable
fun UpdateDialog(release: Updater.Release, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<UpdateStage>(UpdateStage.Prompt) }
    var percent by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        stage = UpdateStage.Downloading
        job = scope.launch {
            stage = runCatching {
                val file = Updater.download(context, release) { percent = (it * 100).toInt() }
                stage = UpdateStage.Installing
                Updater.install(context, file)
                UpdateStage.Installing
            }.getOrElse { UpdateStage.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    Updater.installError?.let { error ->
        LaunchedEffect(error) { if (stage == UpdateStage.Installing) stage = UpdateStage.Failed(error) }
    }

    when (val s = stage) {
        UpdateStage.Prompt -> MenuDialog(
            title = stringResource(R.string.update_title, release.version),
            subtitle = stringResource(R.string.update_subtitle, Updater.currentVersion(context)),
            items = listOf(
                MenuItem(stringResource(R.string.update_now)) { start() },
                MenuItem(stringResource(R.string.update_later)) { },
            ),
            // «Позже» и «Назад» — не спрашиваем неделю.
            onDismiss = {
                if (stage == UpdateStage.Prompt) {
                    Updater.snooze(context)
                    onClose()
                }
            },
        )
        UpdateStage.Downloading -> MenuDialog(
            title = stringResource(R.string.update_downloading),
            subtitle = "$percent %",
            items = listOf(MenuItem(stringResource(R.string.cancel)) { }),
            onDismiss = {
                job?.cancel()
                onClose()
            },
        )
        UpdateStage.Installing -> MenuDialog(
            title = stringResource(R.string.update_installing),
            subtitle = stringResource(R.string.update_installing_hint),
            items = listOf(MenuItem(stringResource(R.string.ok)) { }),
            onDismiss = onClose,
        )
        is UpdateStage.Failed -> MenuDialog(
            title = stringResource(R.string.update_failed, s.message),
            items = listOf(MenuItem(stringResource(R.string.ok)) { }),
            onDismiss = onClose,
        )
    }
}
