package ru.iptvsimple.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeLayoutTest {
    private fun apps(vararg packages: String) = packages.associateWith { InstalledApp(it, it.uppercase(), null, null) }

    @Test
    fun filtersOnceAndKeepsDockAndGridKeys() {
        val config = HomeConfig(rows = listOf(HomeRow(10, "Dock", listOf("b", "missing")), HomeRow(20, "Empty", emptyList())), hidden = setOf("hidden"))
        val lines = homeLines(config, apps("a", "b", "c", "d", "e", "f", "g", "hidden"), "Others")
        assertEquals(listOf("row:10", "row:20", "others:0", "others:1"), lines.map { it.id })
        assertTrue(lines[0].dock)
        assertNull(lines[0].title)
        assertEquals(listOf("b"), lines[0].apps.map { it.pkg })
        assertEquals("Empty", lines[1].title)
        assertEquals("Others", lines[2].title)
        assertNull(lines[3].title)
        assertFalse(lines[2].scrollable)
        assertEquals(listOf("a", "c", "d", "e"), lines[2].apps.map { it.pkg })
        assertEquals(listOf("row:10", "row:20"), homeLines(config.copy(showOthers = false), apps("a", "b"), "Others").map { it.id })
    }

    @Test
    fun clampsColumnAfterRemoval() {
        val line = TileLine("row:10", null, apps("a", "b", "c").values.toList())
        assertEquals("b", line.focusPackage(1))
        assertEquals("c", line.focusPackage(10))
        assertEquals("a", line.focusPackage(-1))
        assertNull(TileLine("empty", null, emptyList()).focusPackage(0))
    }

    @Test
    fun skipsEmptyRowsAndFindsMovedPackageInDestination() {
        val installed = apps("a", "b")
        val config = HomeConfig(rows = listOf(HomeRow(10, "A", listOf("a")), HomeRow(20, "B", emptyList()), HomeRow(30, "C", listOf("b"))))
        val lines = homeLines(config, installed, "Others")
        assertEquals(2, nextFocusLine(lines, 0, up = false))
        assertEquals(0, nextFocusLine(lines, 2, up = true))
        assertNull(nextFocusLine(lines, 0, up = true))
        assertNull(nextFocusLine(lines, 2, up = false))
        val moved = homeLines(config.moveApp("a", 0, 1)!!, installed, "Others")
        assertEquals("row:20", moved.first { it.apps.any { app -> app.pkg == "a" } }.id)
        assertEquals(1, nextFocusLine(moved, 2, up = true))
        assertEquals("a", moved[1].focusPackage(10))
    }
}
