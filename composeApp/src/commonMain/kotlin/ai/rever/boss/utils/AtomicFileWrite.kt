package ai.rever.boss.utils

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

private val OWNER_ONLY_FILE_PERMISSIONS: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
import java.nio.file.attribute.PosixFilePermissions

/**
 * Move [temp] onto this file, replacing it if it already exists.
 *
 * **Do not use `File.renameTo` for this.** Its behaviour when the destination exists is
 * platform-dependent, and the platforms disagree in exactly the way that hides the bug during
 * development: POSIX `rename(2)` replaces the target, so macOS and Linux work, while Win32
 * `MoveFile` fails with `ERROR_ALREADY_EXISTS`, so Windows silently stops overwriting anything
 * after the first write. That cost the browser its favicons on Windows for as long as the cache
 * had an entry — see [ai.rever.boss.cache.FaviconCache].
 *
 * `Files.move` with `REPLACE_EXISTING` is the portable form: on Windows it maps to `MoveFileEx`
 * with `MOVEFILE_REPLACE_EXISTING`. `ATOMIC_MOVE` is requested first because it additionally rules
 * out a torn destination, and is retried without when the move would cross a volume — the only
 * case that raises `AtomicMoveNotSupportedException`. Both callers create their temp file as a
 * sibling of the target, so that fallback should never fire; it is there for a caller that does
 * not. Any other refusal is a plain `IOException` and propagates.
 *
 * @throws IOException if the file could not be replaced.
 */
fun File.atomicMoveFrom(temp: File) {
    try {
        Files.move(
            temp.toPath(),
            toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Write [text] to this file atomically: content goes to a UNIQUE sibling
 * temp file first, then replaces the target via [atomicMoveFrom]. A crash
 * mid-write leaves at most a stray temp file, never a truncated target;
 * concurrent writers each use their own temp file so bytes can't interleave —
 * last move wins.
 *
 * On POSIX filesystems, permissions are pinned to owner read/write (0600)
 * before moving into place so state files do not inherit a permissive umask.
 *
 * Shared by everything that persists small state files, including the workspace
 * layout written on shutdown; `grep atomicWriteText` for the current set rather
 * than trusting a list here, which has gone stale once already. Callers
 * previously open-coded this dance with a FIXED temp name, which concurrent
 * writers could clobber.
 *
 * The parent directory is verified before use: a symlinked parent (or one swapped for a
 * symlink between the check and the move) would redirect both the temp file and the
 * destination wherever the link points, so the write refuses one outright and otherwise
 * runs against the resolved real path.
 */
fun File.atomicWriteText(text: String) {
    val parent = verifiedWriteParent()
    val tmp =
        try {
            Files.createTempFile(
                parent,
                "$name.",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } catch (_: UnsupportedOperationException) {
            // A non-POSIX filesystem has no mode to set; the temp still inherits the
            // directory's ACL, which is the tightest available there.
            Files.createTempFile(parent, "$name.", ".tmp")
        }.toFile()
    try {
        if (Files.getFileAttributeView(tmp.toPath(), PosixFileAttributeView::class.java) != null) {
            // Fail closed if a filesystem advertises POSIX permissions but refuses the
            // restriction. Publishing the temp file anyway would defeat this helper's security
            // contract for every state file that relies on it.
            Files.setPosixFilePermissions(tmp.toPath(), OWNER_ONLY_FILE_PERMISSIONS)
        }
        tmp.writeText(text)
        parent.resolve(name).toFile().atomicMoveFrom(tmp)
    } finally {
        // No-op when the move took it away; cleans up on failure paths.
        tmp.delete()
    }
}

/**
 * The directory the temp file and the move both run in.
 *
 * A swapped or symlinked parent used to redirect the temp file and the destination
 * together. The declared parent is refused when it is itself a symlink; ancestors that are
 * links (macOS `/var`, a symlinked home) stay legal because `toRealPath` pins the write to
 * the directory they resolve to right now - a later swap of the declared path cannot
 * redirect a write that no longer goes through it. Where the filesystem reports a POSIX
 * owner, the directory must also belong to the user running this process.
 *
 * Closing the last sliver of the swap race - a link landing between the refusal check and
 * the resolve - needs a held directory fd (O_NOFOLLOW/openat), which java.nio does not
 * expose; the post-resolve re-check below shrinks that window to the minimum the API
 * allows rather than pretending it is closed.
 */
private fun File.verifiedWriteParent(): Path {
    val declared =
        (parentFile ?: absoluteFile.parentFile)
            ?: throw IOException("Cannot resolve a parent directory for $path")
    declared.mkdirs()
    val declaredPath = declared.toPath()
    declaredPath.throwIfSymlinked()
    val real = declaredPath.toRealPath()
    real.throwIfNotDirectory()
    // Swapped for a link while resolving: real now points wherever the link does.
    declaredPath.throwIfSymlinked()
    real.throwIfNotOwnedByCurrentUser()
    return real
}

private fun Path.throwIfSymlinked() {
    if (Files.isSymbolicLink(this)) {
        throw IOException("Refusing to write through a symlinked directory: $this")
    }
}

private fun Path.throwIfNotDirectory() {
    if (!Files.isDirectory(this)) {
        throw IOException("Refusing to write into a non-directory: $this")
    }
}

private fun Path.throwIfNotOwnedByCurrentUser() {
    val posix = Files.getFileAttributeView(this, PosixFileAttributeView::class.java) ?: return
    val owner = posix.readAttributes().owner().name
    val currentUser = System.getProperty("user.name")
    if (owner != currentUser) {
        throw IOException("Refusing to write into $this: owned by $owner, this process runs as $currentUser")
    }
}
