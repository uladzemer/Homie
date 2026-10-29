package ru.iptvsimple.home

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootTest {
    /** Процесс без su: позволяет проверить остановку, включая отказ завершаться по TERM. */
    private class Command(
        private val code: Int = 0,
        text: String = "",
        private val completes: Boolean = true,
        private val ignoresTerm: Boolean = false,
    ) : Process() {
        val input = ByteArrayOutputStream()
        private val output = ByteArrayInputStream(text.toByteArray())
        var destroyed = false
        var forced = false
        override fun getOutputStream() = input
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = completes || forced || (destroyed && !ignoresTerm)
        override fun exitValue(): Int = code
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { forced = true; return this }
    }

    @Test fun usesExitCodeEvenWhenOutputSaysOk() {
        val failed = Root.execute(Command(7, "ok\nSuccess\n"), "false", 1000)
        assertEquals(7, failed.exitCode)
        assertEquals("ok\nSuccess\n", failed.output)
        assertFalse(failed.success)
        assertFalse(failed.timedOut)
        val success = Root.execute(Command(text = "Success\n"), "true", 1000)
        assertTrue(success.success)
        assertEquals("Success\n", success.output)
    }

    @Test fun destroysTimedOutProcessAndEscalatesIfNeeded() {
        for (ignoresTerm in listOf(false, true)) {
            val command = Command(text = "writing:logo\n", completes = false, ignoresTerm = ignoresTerm)
            val result = Root.execute(command, "sleep 999", 10)
            assertTrue(result.timedOut)
            assertFalse(result.success)
            assertNull(result.exitCode)
            assertTrue(command.destroyed)
            assertEquals(ignoresTerm, command.forced)
        }
    }

    @Test fun recognizesShellWatchdogTimeoutAndBoundsOutput() {
        val result = Root.execute(Command(124, "x".repeat(20000) + "last error\n"), "", 1000)
        assertTrue(result.timedOut)
        assertFalse(result.success)
        assertTrue(result.output.length <= 8192)
        assertTrue(result.output.endsWith("last error\n"))
    }

    @Test fun closesInputAfterPassingTheScript() {
        val command = Command()
        Root.execute(command, "id\nexit 3", 1000)
        assertEquals("id\nexit 3\n", command.input.toString())
    }

    private fun process(mode: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = listOf(RootProcessProbe::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            File(requireNotNull(requireNotNull(it.protectionDomain).codeSource).location.toURI()).path
        }
        return ProcessBuilder(java, "-cp", classpath, RootProcessProbe::class.java.name, mode).redirectErrorStream(true).start()
    }

    @Test fun drainsOutputWhileWritingLargeInputWithoutPipeDeadlock() {
        val result = Root.execute(process("pipes"), "s".repeat(2 * 1024 * 1024), 10000)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.output.endsWith("finished\n"))
    }

    @Test fun timeoutStopsARealProcessWithinBoundedWait() {
        val process = process("sleep")
        val start = System.nanoTime()
        val result = Root.execute(process, "", 100)
        assertTrue(result.timedOut)
        assertFalse(process.isAlive)
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
    }
}

/** Дочерняя JVM проверяет настоящие каналы и остановку процесса, не требуя su или Android. */
object RootProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "sleep") Thread.sleep(10000)
        else {
            repeat(2048) { System.out.print("x".repeat(1024)) }
            System.out.flush()
            System.`in`.bufferedReader().readText()
            System.out.print("finished\n")
            exitProcess(7)
        }
    }
}
