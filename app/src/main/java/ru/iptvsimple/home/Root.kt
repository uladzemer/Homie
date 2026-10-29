package ru.iptvsimple.home

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Команды от root через su; ввод и вывод читаются параллельно, ожидание ограничено. */
object Root {
    data class Output(val exitCode: Int?, val output: String, val timedOut: Boolean = false, val inputFailed: Boolean = false) {
        val success: Boolean get() = exitCode == 0 && !timedOut && !inputFailed
    }

    fun available(): Boolean = run("id", 60).let { it.success && Regex("\\buid=0\\b").containsMatchIn(it.output) }

    fun run(script: String, timeoutSeconds: Long = 60): Output {
        require(timeoutSeconds > 0)
        val process = try { ProcessBuilder("su").redirectErrorStream(true).start() }
        catch (e: java.io.IOException) { return Output(null, e.message.orEmpty()) }
        return execute(process, script, TimeUnit.SECONDS.toMillis(timeoutSeconds))
    }

    internal fun execute(process: Process, script: String, timeoutMillis: Long): Output {
        val output = StringBuilder()
        val ioFailure = AtomicReference<Exception?>()
        val reader = thread(isDaemon = true, name = "root-output") {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(output) {
                            output.append(buffer, 0, n)
                            if (output.length > 8192) output.delete(0, output.length - 8192)
                        }
                    }
                }
            }
        }
        val writer = thread(isDaemon = true, name = "root-input") {
            try { process.outputStream.bufferedWriter().use { it.write(script.trimIndent()); it.write("\n") } }
            catch (e: Exception) { ioFailure.set(e) }
        }
        var finished = false
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            if (!finished) {
                process.destroy()
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1500, TimeUnit.MILLISECONDS)
                }
            }
        }
        writer.join(200)
        reader.join(200)
        val code = if (finished) process.exitValue() else null
        val error = ioFailure.get()
        val text = synchronized(output) { output.toString() } + (error?.let { "\n${it.message}" } ?: "")
        return Output(code, text, !finished || code == 124, error != null)
    }
}
