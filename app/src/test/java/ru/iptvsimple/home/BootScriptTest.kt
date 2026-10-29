package ru.iptvsimple.home

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Настоящая оболочка, но все разделы, точки монтирования и копии — обычные файлы в temp. */
class BootScriptTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("homie-boot-test-").toFile()
        val backup = File(dir, "backup").apply { mkdir() }
        val partition = File(dir, "partition").apply { writeBytes(ByteArray(1024) { 42 }) }
        val odm = File(dir, "odm/logo_files/bootup.bmp").apply { requireNotNull(parentFile).mkdirs(); writeText("BMcurrent") }
        val zip = File(dir, "new.zip").apply { writeText("new animation") }
        init { File(dir, "mounts").writeText("dev ./oem ext4 ro 0 0\n") }

        fun file(name: String) = File(backup, name)
        fun animation(p: String = "oem") = File(dir, "$p/media/bootanimation.zip").also { requireNotNull(it.parentFile).mkdirs() }
        fun run(body: String, before: String = "") = execute(BootScreen.script(body), before)
        fun install(before: String = "") = execute(BootScreen.animationScript("./new.zip"), before)
        fun restore(before: String = "") = execute(BootScreen.animationScript(null), before)
        fun legacy(before: String = "", afterStage: () -> Unit = {}): Root.Output {
            val stage = Files.createTempDirectory(dir.toPath(), "stage-").toFile()
            var phase = 0
            return try {
                BootScreen.restoreLegacy(stage) {
                    if (phase++ == 1) afterStage()
                    execute(it, before)
                }
            } finally { stage.deleteRecursively() }
        }

        private fun execute(source: String, before: String): Root.Output {
            val shell = listOf("C:/Program Files/Git/bin/bash.exe", "/bin/sh").firstOrNull { File(it).isFile }
            assumeTrue("Нужна POSIX-оболочка (в Windows — Git Bash)", shell != null)
            val script = source.replace("/data/local/homie-boot", "./backup").replace("/dev/block/logo", "./partition")
                .replace("/mnt/vendor/odm_ext", "./odm").replace("/proc/mounts", "./mounts")
                .replace("/oem", "./oem").replace("/product", "./product")
            val prelude = """
                PATH="/usr/bin:/bin:${'$'}PATH"
                blockdev() { echo 1024; }
                mount() { echo "${'$'}2 ${'$'}3" >> mounts.log; }
                sync() { return 0; }
            """.trimIndent()
            val process = ProcessBuilder(requireNotNull(shell)).directory(dir).redirectErrorStream(true).start()
            return Root.execute(process, "$prelude\n$before\n$script", 10000)
        }

        fun mounts() = File(dir, "mounts.log").takeIf { it.exists() }?.readText().orEmpty()
        override fun close() { dir.deleteRecursively() }
    }

    companion object {
        private fun image() = ByteArray(1024).also {
            val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            "AML_RES!".toByteArray().copyInto(it, 8)
            b.putInt(4, 2); b.putInt(16, 132); b.putInt(20, 1); b.putInt(24, 64)
            b.putInt(0x48, 4); b.putInt(0x4c, 128)
            b.putInt(0, AmlRes.crc(it, 132))
        }
    }

    @Test fun preflightFailsBeforeAnyWrite() {
        for (tool in listOf("sha256sum", "cmp", "mount", "cp", "mv", "sync")) Fixture().use { f ->
            val out = f.install("command() { [ \"${'$'}2\" != '$tool' ]; }")
            assertEquals(64, out.exitCode, out.output)
            assertTrue("tool $tool missing" in out.output)
            assertFalse(f.file("animation.state").exists())
            assertTrue(f.mounts().isEmpty())
        }
    }

    @Test fun stepPropagatesChildStatusInGuardedContexts() {
        for (body in listOf(
            "step sh -c 'exit 9' || [ \"${'$'}?\" = 9 ]",
            "if step sh -c 'exit 9'; then exit 2; else [ \"${'$'}?\" = 9 ]; fi",
            "if step sh -c 'exit 9' && true; then exit 2; fi",
        )) Fixture().use { f -> assertTrue(f.run("$body\n[ -z \"${'$'}CHILD\" ]").success) }
    }

    @Test fun installOnlyChangesAnimationAndKeepsFirstBackup() = Fixture().use { f ->
        f.animation().writeText("original")
        f.file("logo.img").writeText("untrusted logo")
        f.file("odm_bootup.bmp").writeText("untrusted odm")
        f.file("firmware").writeText("previous firmware")
        val partition = f.partition.readBytes()
        assertTrue(f.install().success)
        assertEquals("./oem present\n", f.file("animation.state").readText())
        assertEquals("original", f.file("bootanimation.zip").readText())
        assertTrue(f.run("verify_animation").success)
        assertEquals("new animation", f.animation().readText())
        f.zip.writeText("second animation")
        assertTrue(f.install().success)
        assertEquals("original", f.file("bootanimation.zip").readText())
        assertContentEquals(partition, f.partition.readBytes())
        assertEquals("BMcurrent", f.odm.readText())
        assertTrue(f.file("logo.img").exists())
        assertTrue(f.restore().success)
        assertEquals("original", f.animation().readText())
        assertTrue(f.mounts().endsWith("remount,ro ./oem\n"))
    }

    @Test fun productDetectionAndOriginallyAbsentRestore() = Fixture().use { f ->
        File(f.dir, "mounts").writeText("dev ./product ext4 ro 0 0\n")
        assertTrue(f.install().success)
        assertEquals("./product absent\n", f.file("animation.state").readText())
        assertFalse(f.file("bootanimation.zip").exists())
        assertTrue(f.animation("product").exists())
        assertTrue(f.restore().success)
        assertFalse(f.animation("product").exists())
    }

    @Test fun legacyAnimationPathKeepsOldRemovalSemantics() {
        for (p in listOf("oem", "product")) Fixture().use { f ->
            f.file("anim_path").writeText("./$p\n")
            f.animation(p).writeText("old Homie animation")
            assertTrue(f.install().success)
            assertEquals("./$p absent\n", f.file("animation.state").readText())
            assertFalse(f.file("bootanimation.zip").exists())
            assertTrue(f.restore().success)
            assertFalse(f.animation(p).exists())
        }
    }

    @Test fun stockWithoutHistoryLeavesFactoryAnimationAlone() = Fixture().use { f ->
        f.animation().writeText("factory")
        assertTrue(f.restore().success)
        assertEquals("factory", f.animation().readText())
        assertFalse(f.file("animation.state").exists())
        assertTrue(f.mounts().isEmpty())
    }

    @Test fun invalidStateAndBackupRefuseBeforeRemount() {
        for (state in listOf("/system absent\n", "./oem unknown\n", "./oem present extra\n", "./oem present\n")) Fixture().use { f ->
            f.file("animation.state").writeText(state)
            assertFalse(f.install().success)
            assertTrue(f.mounts().isEmpty())
        }
        Fixture().use { f ->
            f.file("anim_path").writeText("/system\n")
            assertEquals(66, f.install().exitCode)
            assertFalse(f.file("animation.state").exists())
        }
        Fixture().use { f ->
            f.animation().writeText("original")
            assertTrue(f.install().success)
            for (damage in listOf("missing", "changed")) {
                if (damage == "missing") f.file("bootanimation.zip.sha256").delete()
                else f.file("bootanimation.zip.sha256").writeText("0".repeat(64))
                assertEquals(72, f.restore().exitCode)
                assertEquals("new animation", f.animation().readText())
            }
        }
    }

    @Test fun incompleteBackupPublicationNeverLosesTheOriginal() = Fixture().use { f ->
        f.animation().writeText("original")
        val out = f.install("mv() { case \"${'$'}1\" in */bootanimation.zip.tmp) return 9;; esac; command mv \"${'$'}@\"; }")
        assertEquals(9, out.exitCode, out.output)
        assertTrue(f.file("bootanimation.zip.sha256").exists())
        assertFalse(f.file("animation.state").exists())
        assertEquals("original", f.animation().readText())
        assertEquals(72, f.install().exitCode)
    }

    @Test fun failedAtomicReplaceKeepsTargetAndAlwaysRemountsReadOnly() = Fixture().use { f ->
        f.animation().writeText("original")
        assertTrue(f.run("animation_state install").success)
        val out = f.install("cmp() { return 9; }")
        assertEquals(9, out.exitCode, out.output)
        assertEquals("original", f.animation().readText())
        assertFalse(File(f.animation().path + ".tmp").exists())
        assertTrue(f.mounts().endsWith("remount,ro ./oem\n"))
        assertEquals("original", f.file("bootanimation.zip").readText())
    }

    @Test fun timeoutAlsoRemountsReadOnlyAndKeepsBackup() = Fixture().use { f ->
        f.animation().writeText("original")
        assertTrue(f.run("animation_state install").success)
        val out = f.run("rw /oem\nstep sleep 5", "sleep() { if [ \"${'$'}1\" = 75 ]; then command sleep 0.1; else command sleep \"${'$'}1\"; fi; }")
        assertTrue(out.timedOut, out.output)
        assertTrue(f.mounts().endsWith("remount,ro ./oem\n"))
        assertEquals("original", f.file("bootanimation.zip").readText())
    }

    @Test fun verifiedOldLogoAndBmpRestoreOnceAndDeleteBackups() = Fixture().use { f ->
        f.file("logo.img").writeBytes(image())
        f.file("odm_bootup.bmp").writeText("BMoriginal")
        val out = f.legacy()
        assertTrue(out.success, out.output)
        assertContentEquals(image(), f.partition.readBytes())
        assertEquals("BMoriginal", f.odm.readText())
        assertFalse(f.file("logo.img").exists())
        assertFalse(f.file("odm_bootup.bmp").exists())
        f.partition.writeText("changed later")
        assertTrue(f.legacy().success)
        assertEquals("changed later", f.partition.readText())
    }

    @Test fun unverifiedLogoIsSkippedWhileValidOdmStillRestores() {
        for (bytes in listOf(ByteArray(1024), image().copyOf(512), image().also { it[128] = 1 }, image().also {
            val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0x4c, Int.MAX_VALUE); b.putInt(0, AmlRes.crc(it, 132))
        })) Fixture().use { f ->
            val original = f.partition.readBytes()
            f.file("logo.img").writeBytes(bytes)
            f.file("odm_bootup.bmp").writeText("BMoriginal")
            val out = f.legacy()
            assertTrue(out.success, out.output)
            assertTrue("skipped:logo" in out.output)
            assertContentEquals(original, f.partition.readBytes())
            assertTrue(f.file("logo.img").exists())
            assertEquals("BMoriginal", f.odm.readText())
        }
    }

    @Test fun badBmpAndUnavailableLogoSizeAreSkipped() = Fixture().use { f ->
        f.file("logo.img").writeBytes(image())
        f.file("odm_bootup.bmp").writeText("invalid")
        val original = f.partition.readBytes()
        val out = f.legacy("blockdev() { return 9; }")
        assertTrue(out.success, out.output)
        assertTrue("skipped:logo" in out.output)
        assertTrue("skipped:odm" in out.output)
        assertContentEquals(original, f.partition.readBytes())
        assertEquals("BMcurrent", f.odm.readText())
        assertTrue(f.file("odm_bootup.bmp").exists())
    }

    @Test fun legacyLogoUsesValidatedStagingAndFailedWriteKeepsBackup() = Fixture().use { f ->
        f.file("logo.img").writeBytes(image())
        val out = f.legacy { f.file("logo.img").writeBytes(ByteArray(1024)) }
        assertTrue(out.success, out.output)
        assertContentEquals(image(), f.partition.readBytes())
        f.file("logo.img").writeBytes(image())
        assertEquals(9, f.legacy("dd() { return 9; }").exitCode)
        assertTrue(f.file("logo.img").exists())
    }
}
