package ru.iptvsimple.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CarouselSelectionTest {
    @Test
    fun moveIntoEmptyRowKeepsPackageSelected() {
        val config = HomeConfig(rows = listOf(
            HomeRow(10, "A", listOf("b")),
            HomeRow(20, "B", emptyList()),
            HomeRow(30, "C", listOf("c")),
        ))
        val updated = assertNotNull(config.moveApp("b", 0, 1))
        val position = assertNotNull(carouselPosition(updated.rows, setOf("b", "c"), "b"))
        assertEquals(CarouselPosition(20, 0, 0), position)
        val next = assertNotNull(updated.moveApp(updated.rows.first { it.id == position.rowId }.apps[position.appIndex], 0, 1))
        assertEquals(listOf("b", "c"), next.rows[2].apps)
    }

    @Test
    fun emptyingSourceRowRecomputesVisibleIndex() {
        val config = HomeConfig(rows = listOf(
            HomeRow(10, "A", listOf("a")),
            HomeRow(20, "B", listOf("b", "c")),
        ))
        val updated = assertNotNull(config.moveApp("a", 0, 1))
        val position = assertNotNull(carouselPosition(updated.rows, setOf("a", "b", "c"), "a"))
        assertEquals(CarouselPosition(20, 0, 0), position)
        val next = assertNotNull(updated.moveApp(updated.rows.first { it.id == position.rowId }.apps[position.appIndex], 1, 0))
        assertEquals(listOf("b", "a", "c"), next.rows[1].apps)
        assertEquals(CarouselPosition(20, 0, 1), carouselPosition(next.rows, setOf("a", "b", "c"), "a"))
    }
}
