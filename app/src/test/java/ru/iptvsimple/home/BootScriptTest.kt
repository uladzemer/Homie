package ru.iptvsimple.home

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Настоящая оболочка и обычные файлы вместо разделов; su и записи в прошивку здесь нет. */
class BootScriptTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("homie-boot-test-").toFile()
        val backup = File(dir, "backup").apply { mkdir() }
        val partition = File(dir, "partition").apply { writeBytes(image()) }
        private val base = dir.absolutePath.replace('\\', '/')

        fun file(name: String) = File(backup, name)
        fun save(name: String, bytes: ByteArray = image()) {
            file(name).writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            file("$name.sha256").writeText("$hash\n")
        }

        fun run(body: String, before: String = ""): Root.Output {
            val shell = listOf("C:/Program Files/Git/bin/bash.exe", "/bin/sh").firstOrNull { File(it).isFile }
            assumeTrue("Нужна POSIX-оболочка (в Windows — Git Bash)", shell != null)
            val script = BootScreen.script(body)
                .replace("/data/local/homie-boot", "$base/backup")
                .replace("/dev/block/logo", "$base/partition")
                .replace("/mnt/vendor/odm_ext", "$base/odm")
            val prelude = """
                PATH="/usr/bin:/bin:${'$'}PATH"
                getprop() { echo test-firmware; }
                blockdev() { echo 1024; }
                mount() { return 0; }
                sync() { return 0; }
            """.trimIndent()
            val process = ProcessBuilder(requireNotNull(shell)).redirectErrorStream(true).start()
            return Root.execute(process, "$prelude\n$before\n$script", 10000)
        }

        override fun close() { dir.deleteRecursively() }
    }

    companion object {
        private fun image() = ByteArray(1024).also { "AML_RES!".toByteArray().copyInto(it, 8) }
    }

    @Test fun missingToolsFailBeforeAnyWrite() {
        for (tool in listOf("blockdev", "sha256sum", "stat", "cmp", "dd", "mount")) Fixture().use { f ->
            val out = f.run("echo touched > /data/local/homie-boot/touched", "command() { [ \"${'$'}2\" != '$tool' ]; }")
            assertEquals(64, out.exitCode, out.output)
            assertTrue("tool $tool missing" in out.output)
            assertFalse(f.file("touched").exists())
        }
    }

    @Test fun oldLogoIsAdoptedOnlyWithPartitionSizeAndAmlMagicAtOffsetEight() {
        Fixture().use { f ->
            f.file("logo.img").writeBytes(image())
            val out = f.run("check_firmware\nmigrate_logo\nverify_backups\nbind_firmware")
            assertTrue(out.success, out.output)
            assertTrue(f.file("logo.img.sha256").isFile)
            assertEquals("test-firmware\n", f.file("firmware").readText())
        }
        val invalid = listOf(ByteArray(1024), image().copyOf(512), ByteArray(8), image().also {
            "AML_RES!".toByteArray().copyInto(it, 0)
            it[8] = 0
        })
        for (bytes in invalid) Fixture().use { f ->
            f.file("logo.img").writeBytes(bytes)
            val out = f.run("migrate_logo")
            assertEquals(67, out.exitCode, out.output)
            assertTrue("old logo backup cannot be trusted" in out.output)
            assertFalse(f.file("logo.img.sha256").exists())
            assertContentEquals(image(), f.partition.readBytes())
        }
    }

    @Test fun oldAnimationPathMigratesWithOldRemovalSemantics() {
        for (path in listOf("/oem", "/product")) Fixture().use { f ->
            f.file("anim_path").writeText("$path\n")
            val out = f.run("migrate_animation\nanimation_state")
            assertTrue(out.success, out.output)
            assertEquals("$path absent\n", f.file("animation.state").readText())
        }
        for (path in listOf("", "/system", "/oem/media", "/oem extra", "/oem\n/product")) Fixture().use { f ->
            f.file("anim_path").writeText(path)
            assertEquals(73, f.run("migrate_animation").exitCode)
            assertFalse(f.file("animation.state").exists())
        }
    }

    @Test fun temporaryFilesAloneAreNotABackup() = Fixture().use { f ->
        for (name in listOf("logo.img.tmp", "logo.img.sha256.tmp", "animation.state.tmp", "firmware.tmp")) {
            f.file(name).writeText("interrupted")
        }
        assertTrue(f.run("migrate_logo\nmigrate_animation\nverify_backups").success)
        assertFalse(f.file("logo.img").exists())
    }

    @Test fun publishingHashFirstMakesInterruptedPublicationFailSafe() = Fixture().use { f ->
        f.file("logo.img.tmp").writeBytes(image())
        val out = f.run("publish_file /data/local/homie-boot/logo.img", """
            mv() {
              case "${'$'}1" in */logo.img.tmp) return 9;; esac
              command mv "${'$'}@"
            }
        """.trimIndent())
        assertEquals(9, out.exitCode, out.output)
        assertFalse(f.file("logo.img").exists())
        assertTrue(f.file("logo.img.sha256").isFile)
        assertEquals(65, f.run("verify_backups").exitCode)
    }

    @Test fun fileBackupsHaveChecksumsAndRejectMissingOrDamagedSidecars() {
        for (name in listOf("odm_bootup.bmp", "bootanimation.zip")) Fixture().use { f ->
            val out = f.run("save_file /dev/block/logo /data/local/homie-boot/$name\nverify_backups")
            assertTrue(out.success, out.output)
            assertContentEquals(image(), f.file(name).readBytes())
            f.file("$name.sha256").delete()
            assertEquals(72, f.run("verify_backups").exitCode)
            f.save(name)
            f.file(name).appendText("damage")
            assertEquals(72, f.run("verify_backups").exitCode)
        }
    }

    @Test fun animationStateCannotRestoreAnUnverifiedFile() = Fixture().use { f ->
        f.file("animation.state").writeText("/product present\n")
        f.file("bootanimation.zip").writeText("no sidecar")
        assertEquals(72, f.run("animation_state").exitCode)
        f.save("bootanimation.zip")
        assertTrue(f.run("animation_state").success)
    }

    @Test fun modernLogoWithLostChecksumCannotBeAdoptedAsLegacy() = Fixture().use { f ->
        f.file("firmware").writeText("test-firmware\n")
        f.file("logo.img").writeBytes(image())
        assertEquals(65, f.run("check_firmware\nmigrate_logo").exitCode)
        assertFalse(f.file("logo.img.sha256").exists())
    }

    @Test fun changedFirmwareRefusesBeforeMigrationOrWrite() = Fixture().use { f ->
        f.file("firmware").writeText("previous-firmware\n")
        f.file("logo.img").writeBytes(image())
        val out = f.run("check_firmware\nmigrate_logo\necho touched > /dev/block/logo")
        assertEquals(68, out.exitCode, out.output)
        assertTrue("firmware changed, backups are from previous firmware" in out.output)
        assertFalse(f.file("logo.img.sha256").exists())
        assertContentEquals(image(), f.partition.readBytes())
    }

    @Test fun partitionErrorsHaveTheirOwnCode() = Fixture().use { f ->
        val out = f.run("partition_size", "blockdev() { echo bad-size; }")
        assertEquals(70, out.exitCode, out.output)
        assertTrue("logo partition size invalid" in out.output)
    }

    @Test fun failureAfterLogoWriteRollsBackIncludingWatchdogAndRemountFailure() {
        for (failure in listOf("step cmp -s /dev/null /dev/block/logo", "exit 7", "kill -TERM \"${'$'}MAIN\"", "rw /product")) {
            Fixture().use { f ->
                f.save("logo.img")
                val out = f.run("""
                    check_firmware
                    verify_logo /data/local/homie-boot/logo.img
                    ROLLBACK=1
                    echo writing:logo
                    echo changed > /dev/block/logo
                    $failure
                """.trimIndent(), "mount() { return 1; }")
                assertFalse(out.success, out.output)
                assertTrue("rolled back:logo" in out.output, out.output)
                assertContentEquals(image(), f.partition.readBytes())
            }
        }
    }

    @Test fun rollbackRefusesBackupDamagedAfterInitialVerification() = Fixture().use { f ->
        f.save("logo.img")
        val out = f.run("""
            check_firmware
            verify_logo /data/local/homie-boot/logo.img
            ROLLBACK=1
            echo writing:logo
            echo changed > /dev/block/logo
            echo damage > /data/local/homie-boot/logo.img
            exit 7
        """.trimIndent())
        assertEquals(76, out.exitCode, out.output)
        assertTrue("rollback failed:logo" in out.output)
        assertEquals("changed\n", f.partition.readText())
    }

    @Test fun rollbackReportsAWriteFailure() = Fixture().use { f ->
        f.save("logo.img")
        val out = f.run("""
            check_firmware
            verify_logo /data/local/homie-boot/logo.img
            ROLLBACK=1
            echo writing:logo
            dd() { return 8; }
            exit 7
        """.trimIndent())
        assertEquals(76, out.exitCode, out.output)
        assertTrue("rollback failed:logo" in out.output)
    }
}
