package ai.rever.boss.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Tests for the atomic file-replacement helpers.
 *
 * These exist because of a platform split that hides itself during development: `File.renameTo`
 * replaces an existing destination on macOS and Linux (POSIX `rename(2)`) but fails on Windows
 * (`MoveFile` returns `ERROR_ALREADY_EXISTS`). The browser's favicon cache open-coded that call, so
 * on Windows it wrote each icon exactly once and every later save failed — and because the cache
 * survives restarts, that meant favicons stopped updating entirely.
 *
 * The overwrite tests below therefore only *fail* on Windows. They are worth keeping anyway: PR CI
 * runs `build-test (windows-latest)`, which is precisely the leg that would have caught it.
 */
class AtomicFileWriteTest {
    private val tempDir: File =
        File.createTempFile("atomic-write-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `atomicMoveFrom replaces a file that already exists`() {
        // The regression. On Windows this threw before the fix; on POSIX it always passed.
        val target = File(tempDir, "icon.png").apply { writeText("old") }
        val temp = File(tempDir, "icon.tmp").apply { writeText("new") }

        target.atomicMoveFrom(temp)

        assertEquals("new", target.readText())
        assertFalse(temp.exists(), "the source should have been moved, not copied")
    }

    @Test
    fun `atomicMoveFrom creates the file when it does not exist`() {
        val target = File(tempDir, "fresh.png")
        val temp = File(tempDir, "fresh.tmp").apply { writeText("content") }

        target.atomicMoveFrom(temp)

        assertEquals("content", target.readText())
    }

    @Test
    fun `atomicWriteText overwrites existing content`() {
        val target = File(tempDir, "registry.json")

        target.atomicWriteText("first")
        assertEquals("first", target.readText())

        target.atomicWriteText("second")
        assertEquals("second", target.readText())
    }

    @Test
    fun `atomicWriteText creates missing parent directories`() {
        val target = File(tempDir, "nested/deeper/registry.json")

        target.atomicWriteText("value")

        assertEquals("value", target.readText())
    }

    @Test
    fun `atomicWriteText leaves no temp files behind`() {
        // The temp file is a sibling of the target, so a leak would accumulate in the real cache
        // and config directories rather than in the OS temp dir.
        val target = File(tempDir, "clean.json")

        repeat(3) { target.atomicWriteText("write $it") }

        val strays = tempDir.listFiles()?.filter { it.name != target.name }.orEmpty()
        assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
    }

    @Test
    fun `atomicWriteText restricts file permissions to owner only on posix filesystems`() {
        val target = File(tempDir, "owner-only.json")
        target.atomicWriteText("sensitive-content")

        assertEquals("sensitive-content", target.readText())

        val path = target.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            return
        }

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "File permissions must be owner-only (0600), got: $actual")
    }

    @Test
    fun `atomicWriteText preserves owner only permissions across overwrites`() {
        val target = File(tempDir, "owner-only-overwrite.json")
        target.atomicWriteText("initial-content")
        target.atomicWriteText("updated-content")

        assertEquals("updated-content", target.readText())

        val path = target.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            return
        }

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "File permissions must remain owner-only (0600) on overwrite, got: $actual")
    }

    @Test
    fun `atomicWriteText refuses a symlinked parent`() {
        // The redirect shape: the declared parent is a link to another directory, so an
        // unverified write lands the temp file and the moved-in target on the far side.
        val attackerDir = File(tempDir, "attacker").apply { mkdirs() }
        val link = File(tempDir, "linked-parent")
        try {
            Files.createSymbolicLink(link.toPath(), attackerDir.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem does not support symlinks: ${e.message}")
        } catch (e: IOException) {
            assumeTrue(false, "Could not create a symlink (Windows needs privileges): ${e.message}")
        }

        assertFailsWith<IOException> { File(link, "stolen.json").atomicWriteText("payload") }

        assertFalse(
            File(attackerDir, "stolen.json").exists(),
            "the write must not land in the directory the link points at",
        )
        assertTrue(
            attackerDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
            "no temp file may be created through the link either",
        )
    }

    @Test
    fun `atomicWriteText still writes when an ancestor is a symlink`() {
        // macOS temp dirs live under /var -> /private/var, and users legitimately symlink
        // their config home elsewhere; only the immediate parent is refused as a link.
        val real = File(tempDir, "real-ancestor").apply { mkdirs() }
        val link = File(tempDir, "linked-ancestor")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem does not support symlinks: ${e.message}")
        } catch (e: IOException) {
            assumeTrue(false, "Could not create a symlink (Windows needs privileges): ${e.message}")
        }

        File(link, "nested").let { it.mkdirs() }
        val target = File(link, "nested/state.json")
        target.atomicWriteText("value")

        assertEquals("value", File(real, "nested/state.json").readText())
    }
}
