package ru.iptvsimple.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ряд плиток на главном экране: название и пакеты приложений по порядку. */
data class HomeRow(val id: Long, val title: String, val apps: List<String>)

/** Размер плиток. Ширина — в dp расчётного экрана 960×540 (см. [ScreenDensity]). */
enum class TileSize(val title: String, val width: Float) {
    Small("Маленькие", 128f),
    Normal("Обычные", 160f),
    Large("Крупные", 204f),
    Huge("Очень крупные", 270f),
}

/** Размер надписей: множитель к обычному. */
enum class TextSize(val title: String, val scale: Float) {
    Normal("Обычные", 1f),
    Large("Крупные", 1.3f),
    Huge("Очень крупные", 1.65f),
}

/** Где показывать названия приложений. */
enum class LabelMode(val title: String) {
    Always("Под каждой плиткой"),
    Focused("Только у выбранной"),
}

/** Вид главного экрана. */
enum class HomeStyle(val title: String) {
    Modern("Современный: ряды плиток"),
    Classic("Классика: карусель обложек"),
}

/** Фон главного экрана. */
enum class Backdrop(val title: String) {
    PhotosRotating("Природа, меняется каждые 10 минут"),
    PhotoFixed("Одна картинка"),
    Dark("Тёмный"),
    Blue("Синий"),
}

/** Всё, что пользователь настраивает. Хранится целиком одной строкой JSON. */
data class HomeConfig(
    val rows: List<HomeRow>,
    val style: HomeStyle = HomeStyle.Modern,
    val hidden: Set<String> = emptySet(),
    val showOthers: Boolean = true,
    val tileSize: TileSize = TileSize.Normal,
    val textSize: TextSize = TextSize.Normal,
    val labelMode: LabelMode = LabelMode.Focused,
    val backdrop: Backdrop = Backdrop.PhotosRotating,
    val photoIndex: Int = 0,
    val showClock: Boolean = true,
    /** Приложение, которое открывается само после включения приставки; null — никакое. */
    val autostart: String? = null,
) {
    /** Все пакеты, уже стоящие в рядах. */
    val placed: Set<String> get() = rows.flatMapTo(HashSet()) { it.apps }

    fun nextRowId(): Long = (rows.maxOfOrNull { it.id } ?: 0L) + 1

    fun updateRow(id: Long, change: (HomeRow) -> HomeRow) =
        copy(rows = rows.map { if (it.id == id) change(it) else it })

    /** Убирает приложение из всех рядов: оно уходит в «Остальные приложения». */
    fun removeApp(pkg: String) = copy(rows = rows.map { it.copy(apps = it.apps - pkg) })

    /** Ставит приложение в ряд [rowId] на место [index] (в конец, если индекс вне ряда). */
    fun putApp(pkg: String, rowId: Long, index: Int = Int.MAX_VALUE): HomeConfig {
        val cleared = removeApp(pkg)
        return cleared.updateRow(rowId) { row ->
            val list = row.apps.toMutableList()
            list.add(index.coerceIn(0, list.size), pkg)
            row.copy(apps = list)
        }
    }

    /**
     * Сдвиг плитки при перестановке: [dx] — по ряду, [dy] — в соседний ряд на то же место.
     * null — двигать некуда.
     */
    fun moveApp(pkg: String, dx: Int, dy: Int): HomeConfig? {
        val r = rows.indexOfFirst { pkg in it.apps }.takeIf { it >= 0 } ?: return null
        val index = rows[r].apps.indexOf(pkg)
        return if (dy == 0) {
            val target = index + dx
            if (target !in rows[r].apps.indices) null else putApp(pkg, rows[r].id, target)
        } else {
            val targetRow = r + dy
            if (targetRow !in rows.indices) null else putApp(pkg, rows[targetRow].id, index)
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("rows", JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().apply {
                    put("id", row.id)
                    put("title", row.title)
                    put("apps", JSONArray(row.apps))
                })
            }
        })
        put("hidden", JSONArray(hidden.toList()))
        put("showOthers", showOthers)
        put("style", style.name)
        put("tileSize", tileSize.name)
        put("textSize", textSize.name)
        put("labelMode", labelMode.name)
        put("backdrop", backdrop.name)
        put("photoIndex", photoIndex)
        put("showClock", showClock)
        autostart?.let { put("autostart", it) }
    }.toString()

    companion object {
        fun fromJson(text: String): HomeConfig {
            val o = JSONObject(text)
            val rows = o.optJSONArray("rows").objects().map { r ->
                HomeRow(r.getLong("id"), r.getString("title"), r.optJSONArray("apps").strings())
            }
            return HomeConfig(
                rows = rows,
                hidden = o.optJSONArray("hidden").strings().toSet(),
                showOthers = o.optBoolean("showOthers", true),
                style = enumOr(o.optString("style"), HomeStyle.Modern),
                tileSize = enumOr(o.optString("tileSize"), TileSize.Normal),
                textSize = enumOr(o.optString("textSize"), TextSize.Normal),
                labelMode = enumOr(o.optString("labelMode"), LabelMode.Focused),
                backdrop = enumOr(o.optString("backdrop"), Backdrop.PhotosRotating),
                photoIndex = o.optInt("photoIndex", 0),
                showClock = o.optBoolean("showClock", true),
                autostart = o.optString("autostart").takeIf { it.isNotEmpty() },
            )
        }

        /**
         * Первый запуск: один ряд «Избранное» с известными видео-приложениями, какие есть на
         * приставке, остальное — в «Остальных приложениях».
         */
        fun initial(installed: Collection<String>): HomeConfig {
            val favourites = KnownApps.favourites.filter { it in installed }.take(6)
            return HomeConfig(rows = listOf(HomeRow(1, "Избранное", favourites)))
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else List(length()) { getString(it) }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { getJSONObject(it) }
    }
}

/** Пакеты, которые разумно сразу поставить в «Избранное». */
object KnownApps {
    val favourites = listOf(
        "ru.iptvsimple.tv",
        "app.smarttube.fdroid",
        "com.teamsmart.videomanager.tv",
        "com.google.android.youtube.tv",
        "com.vk.tv",
        "ru.rutube.app.tv",
        "ru.kinopoisk.tv",
        "ru.ivi.client",
        "ru.okko.tv",
        "ru.more.play",
        "com.netflix.ninja",
    )

    /** Другие оболочки: их плитки на главном экране только путают. */
    val neverShown = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.android.launcher3",
        "com.google.android.apps.tv.launcherx",
    )
}

/** Хранение настроек в SharedPreferences. Чтение и запись — с главного потока, данные крошечные. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(): HomeConfig? = prefs.getString(KEY, null)?.let {
        runCatching { HomeConfig.fromJson(it) }.getOrNull()
    }

    fun save(config: HomeConfig) {
        prefs.edit().putString(KEY, config.toJson()).apply()
    }

    /** Номер загрузки приставки, при которой автозапуск уже сработал. */
    var autostartBoot: String?
        get() = prefs.getString(KEY_BOOT, null)
        set(value) { prefs.edit().putString(KEY_BOOT, value).apply() }

    private companion object {
        const val KEY = "config"
        const val KEY_BOOT = "autostart_boot"
    }
}
