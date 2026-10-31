package ru.iptvsimple.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvInputsTest {
    private val hdmi = "com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384"

    @Test
    fun inputIdsKeepTheCompleteSystemId() {
        val id = TvInputs.PREFIX + hdmi
        assertEquals("input:$hdmi", id)
        assertTrue(TvInputs.isInput(id))
        assertEquals(hdmi, TvInputs.inputId(id))
        assertFalse(TvInputs.isInput("com.tcl.tv"))
        assertFalse(TvInputs.isInput("com.example.input:service"))
        assertFalse(TvInputs.isInput(""))
        assertEquals("com.tcl.tv", TvInputs.inputId("com.tcl.tv"))
        assertEquals("input:nested", TvInputs.inputId("input:input:nested"))
    }

    @Test
    fun initialConfigPrependsAllInputsInInstalledOrder() {
        val inputs = listOf("input:$hdmi", "input:tuner", "input:hdmi2", "input:hdmi3", "input:hdmi4")
        val config = HomeConfig.initial(listOf("com.tcl.tv", inputs[0], "com.netflix.ninja") + inputs.drop(1), "Favourites", "Inputs")
        assertEquals(listOf("Inputs", "Favourites"), config.rows.map { it.title })
        assertEquals(inputs, config.rows.first().apps)
        assertEquals(listOf("com.netflix.ninja"), config.rows.last().apps)
        assertEquals(config.rows.size, config.rows.map { it.id }.toSet().size)
        assertEquals((inputs + "com.netflix.ninja").toSet(), config.placed)
    }

    @Test
    fun initialConfigWithoutInputsKeepsExistingRows() {
        val installed = KnownApps.favourites.reversed() + "com.tcl.tv"
        val config = HomeConfig.initial(installed, "Favourites", "Inputs")
        assertEquals(listOf(HomeRow(1, "Favourites", KnownApps.favourites.take(6))), config.rows)
        assertEquals(listOf(HomeRow(1, "Favourites", emptyList())), HomeConfig.initial(emptyList(), "Favourites", "Inputs").rows)
    }
}

class TvInputsHardwareTest {
    @Test
    fun onlyHardwareInputsAreShown() {
        assertTrue(TvInputs.isHardware("com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744384"))
        assertTrue(TvInputs.isHardware("com.tcl.tvinput/.TDTVInputService/HW1413742848"))
        assertTrue(TvInputs.isHardware("com.droidlogic.tvinput/.services.Hdmi1InputService/HDMI1413744128"))
        assertFalse(TvInputs.isHardware("ru.iptvsimple.tv/.ChannelInputService"))
        assertFalse(TvInputs.isHardware("com.example/.Service/hw1"))
    }
}
