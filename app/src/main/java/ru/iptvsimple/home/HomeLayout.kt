package ru.iptvsimple.home

/** Отдельная строка сетки тоже живёт в ленивом списке; ключ не зависит от её номера. */
internal class TileLine(val id: String, val title: String?, val apps: List<InstalledApp>, val dock: Boolean = false, val scrollable: Boolean = true) {
    fun focusPackage(saved: String?, column: Int): String? =
        saved?.takeIf { pkg -> apps.any { it.pkg == pkg } } ?: apps.getOrNull(column.coerceIn(0, (apps.size - 1).coerceAtLeast(0)))?.pkg
}

internal fun homeLines(config: HomeConfig, apps: Map<String, InstalledApp>, othersTitle: String): List<TileLine> {
    val placed = config.placed
    return buildList {
        config.rows.forEachIndexed { i, row ->
            add(TileLine("row:${row.id}", row.title.takeIf { i != 0 }, row.apps.mapNotNull { apps[it] }, dock = i == 0))
        }
        if (config.showOthers) {
            val others = apps.values.filter { it.pkg !in placed && it.pkg !in config.hidden }.sortedBy { it.label.lowercase() }
            // Сеткой: столько плиток в строке, сколько помещается по ширине.
            val perLine = ((960f - 88f - 28f + 18f) / (config.tileSize.width + 18f)).toInt().coerceAtLeast(1)
            others.chunked(perLine).forEachIndexed { i, line ->
                add(TileLine("others:${line.first().pkg}", othersTitle.takeIf { i == 0 }, line, scrollable = false))
            }
        }
    }
}

/** Пустые ряды видны, но не становятся остановками D-pad. */
internal fun nextFocusLine(lines: List<TileLine>, row: Int, up: Boolean): Int? =
    (if (up) row - 1 downTo 0 else row + 1..lines.lastIndex).firstOrNull { lines[it].apps.isNotEmpty() }
