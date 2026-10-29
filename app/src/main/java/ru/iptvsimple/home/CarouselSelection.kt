package ru.iptvsimple.home

internal data class CarouselPosition(val rowId: Long, val shelfIndex: Int, val appIndex: Int)

/** Исчезнувший ряд заменяется первым видимым; null — «Остальные» или пустой экран. */
internal fun visibleCarouselRow(selected: Long?, visible: List<Long?>): Long? =
    if (selected in visible) selected else visible.firstOrNull()

/** Индексы только видимых рядов; пакет и id ряда не меняются при исчезновении соседнего ряда. */
internal fun carouselPosition(rows: List<HomeRow>, installed: Set<String>, pkg: String): CarouselPosition? {
    var shelfIndex = 0
    for (row in rows) {
        val shown = row.apps.filter { it in installed }
        if (shown.isEmpty()) continue
        val index = shown.indexOf(pkg)
        if (index >= 0) return CarouselPosition(row.id, shelfIndex, index)
        shelfIndex++
    }
    return null
}
