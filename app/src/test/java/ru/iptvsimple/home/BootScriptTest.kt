package ru.iptvsimple.home

import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        fun run(body: String, before: String = ""): Root.Output = execute(BootScreen.script(body), before)
        fun migrate(before: String = "", afterStage: () -> Unit = {}): Root.Output {
            var phase = 0
            return BootScreen.migrateLegacy(dir) { script ->
                if (phase++ == 1) afterStage()
                execute(script, before)
            }
        }

        private fun execute(source: String, before: String): Root.Output {
            val shell = listOf("C:/Program Files/Git/bin/bash.exe", "/bin/sh").firstOrNull { File(it).isFile }
            assumeTrue("Нужна POSIX-оболочка (в Windows — Git Bash)", shell != null)
            val script = source
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
        private fun image() = ByteArray(1024).also {
            val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            "AML_RES!".toByteArray().copyInto(it, 8)
            b.putInt(4, 2)
            b.putInt(16, 132)
            b.putInt(20, 1)
            b.putInt(24, 64)
            b.putInt(0x48, 4)
            b.putInt(0x4c, 128)
            "bootup".toByteArray().copyInto(it, 0x60)
            b.putInt(0, AmlRes.crc(it, 132))
        }
    }

    @Test fun missingToolsFailBeforeAnyWrite() {
        for (tool in listOf("blockdev", "sha256sum", "stat", "cmp", "dd", "mount")) Fixture().use { f ->
            val out = f.run("echo touched > /data/local/homie-boot/touched", "command() { [ \"${'$'}2\" != '$tool' ]; }")
            assertEquals(64, out.exitCode, out.output)
            assertTrue("tool $tool missing" in out.output)
            assertFalse(f.file("touched").exists())
        }
    }

    @Test fun stepPreservesStatusInGuardedContextsAndClearsChild() {
        for (body in listOf(
            "step sh -c 'exit 9' || [ \"${'$'}?\" = 9 ]",
            "if step sh -c 'exit 9'; then exit 2; else [ \"${'$'}?\" = 9 ]; fi",
            "if step sh -c 'exit 9' && true; then exit 2; fi",
        )) Fixture().use { f ->
            val out = f.run("$body\n[ -z \"${'$'}CHILD\" ]")
            assertTrue(out.success, out.output)
        }
    }

    @Test fun guardedValidatorsAndBackupCopyDoNotIgnoreErrors() = Fixture().use { f ->
        assertTrue(f.run("if aml_header /dev/null; then exit 2; fi\nEXPECTED=bad\nif valid_hash; then exit 3; fi").success)
        f.save("logo.img")
        assertEquals(65, f.run("if verify_logo /data/local/homie-boot/logo.img; then exit 2; fi", "sha256sum() { return 9; }").exitCode)
        assertEquals(72, f.run("if verify_file /data/local/homie-boot/logo.img; then exit 2; fi", "sha256sum() { return 9; }").exitCode)
        val out = f.run("save_file /dev/block/logo /data/local/homie-boot/copied || exit \"${'$'}?\"", "cp() { return 9; }")
        assertEquals(9, out.exitCode, out.output)
        assertFalse(f.file("copied").exists())
    }

    @Test fun oldLogoIsAdoptedOnlyAfterFullAmlValidationAndKeepsUnknownProvenance() {
        Fixture().use { f ->
            f.file("logo.img").writeBytes(image())
            val out = f.migrate()
            assertTrue(out.success, out.output)
            assertTrue(f.file("logo.img.sha256").isFile)
            assertEquals("legacy\n", f.file("firmware").readText())
            assertTrue(f.run("check_firmware\nverify_backups\nbind_firmware").success)
            assertEquals("legacy\n", f.file("firmware").readText())
        }
        val invalid = listOf(ByteArray(1024), image().copyOf(512), ByteArray(8), image().also { it[128] = 1 }, image().also {
            val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0x4c, Int.MAX_VALUE)
            b.putInt(0, AmlRes.crc(it, 132))
        }, image().also {
            "AML_RES!".toByteArray().copyInto(it, 0)
            it[8] = 0
        })
        for (bytes in invalid) Fixture().use { f ->
            f.file("logo.img").writeBytes(bytes)
            val out = f.migrate()
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
            assertEquals("legacy\n", f.file("firmware").readText())
        }
        for (path in listOf("", "/system", "/oem/media", "/oem extra", "/oem\n/product")) Fixture().use { f ->
            f.file("anim_path").writeText(path)
            assertEquals(73, f.run("migrate_animation").exitCode)
            assertFalse(f.file("animation.state").exists())
        }
    }

    @Test fun oldOdmBackupNeedsBmpMagicAndKeepsLegacyBinding() {
        Fixture().use { f ->
            f.file("odm_bootup.bmp").writeText("BMold-bitmap")
            assertTrue(f.migrate().success)
            assertTrue(f.run("verify_backups\ncheck_firmware\nbind_firmware").success)
            assertEquals("legacy\n", f.file("firmware").readText())
        }
        for (bytes in listOf(byteArrayOf(), "B".toByteArray(), "wrong".toByteArray())) Fixture().use { f ->
            f.file("odm_bootup.bmp").writeBytes(bytes)
            val out = f.migrate()
            assertEquals(67, out.exitCode, out.output)
            assertFalse(f.file("odm_bootup.bmp.sha256").exists())
        }
        Fixture().use { f ->
            f.file("odm_bootup.bmp").writeText("BMmodern")
            f.file("firmware").writeText("test-firmware\n")
            assertEquals(72, f.migrate().exitCode)
        }
    }

    @Test fun legacyLogoChangedAfterStagingCannotBeAdopted() = Fixture().use { f ->
        f.file("logo.img").writeBytes(image())
        val out = f.migrate { f.file("logo.img").appendText("changed") }
        assertEquals(67, out.exitCode, out.output)
        assertFalse(f.file("logo.img.sha256").exists())
        assertFalse(f.file("firmware").exists())
    }

    @Test fun legacyHashAlwaysBelongsToTheParsedStagingFile() = Fixture().use { f ->
        f.file("logo.img").writeBytes(image())
        val backup = f.file("logo.img").invariantSeparatorsPath
        val out = f.migrate("cmp() { command cmp \"${'$'}@\" || return ${'$'}?; echo changed >> '$backup'; }")
        assertTrue(out.success, out.output)
        assertEquals(65, f.run("verify_backups").exitCode)
        assertContentEquals(image(), f.partition.readBytes())
    }

    @Test fun temporaryFilesAloneAreNotABackup() = Fixture().use { f ->
        for (name in listOf("logo.img.tmp", "logo.img.sha256.tmp", "animation.state.tmp", "firmware.tmp")) {
            f.file(name).writeText("interrupted")
        }
        assertTrue(f.migrate().success)
        assertTrue(f.run("verify_backups").success)
        assertFalse(f.file("logo.img").exists())
        assertFalse(f.file("firmware").exists())
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
        assertEquals(65, f.migrate().exitCode)
        assertFalse(f.file("logo.img.sha256").exists())
    }

    @Test fun changedFirmwareRefusesBeforeMigrationOrWrite() = Fixture().use { f ->
        f.file("firmware").writeText("previous-firmware\n")
        f.file("logo.img").writeBytes(image())
        val out = f.migrate()
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
                if (failure == "rw /product") {
                    assertTrue(out.output.indexOf("remount-ro:/product") < out.output.indexOf("rolled back:logo"), out.output)
                }
                assertContentEquals(image(), f.partition.readBytes())
            }
        }
    }

    @Test fun finalRemountFailureDoesNotUndoASuccessfulInstall() = Fixture().use { f ->
        f.save("logo.img")
        val out = f.run("""
            ROLLBACK=1
            echo changed > /dev/block/logo
            rw /product
        """.trimIndent(), "mount() { [ \"${'$'}2\" = remount,rw ]; }")
        assertEquals(74, out.exitCode, out.output)
        assertTrue("remount-ro:/product" in out.output)
        assertFalse("rolled back:logo" in out.output)
        assertEquals("changed\n", f.partition.readText())
    }

    @Test fun rollbackWatchdogStopsAStalledWrite() = Fixture().use { f ->
        f.save("logo.img")
        val out = f.run("""
            ROLLBACK=1
            dd() { command sleep 2; echo late > /dev/block/logo; }
            exit 7
        """.trimIndent(), "sleep() { if [ \"${'$'}1\" = 25 ]; then command sleep 0.1; else command sleep \"${'$'}1\"; fi; }")
        assertEquals(76, out.exitCode, out.output)
        assertTrue("rollback failed:logo (timeout)" in out.output)
        assertContentEquals(image(), f.partition.readBytes())
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
