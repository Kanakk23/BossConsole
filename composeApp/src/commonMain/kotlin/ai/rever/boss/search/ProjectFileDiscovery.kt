package ai.rever.boss.search

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class ProjectFile(
    val file: File,
    val relativePath: String,
)

/** A bounded walk never returns partial results as if they were complete. */
internal data class ProjectDiscoveryResult(
    val files: List<ProjectFile>,
    val incompleteReason: String? = null,
)

/** Thrown by content search rather than silently returning a trustworthy-looking partial list. */
internal class ProjectDiscoveryIncompleteException(
    reason: String,
) : IllegalStateException(reason)

/**
 * Project-tree policy shared by filename indexing and content search.
 *
 * `.gitignore` follows Git's working-tree pattern grammar: ordered nested files, negation,
 * anchoring, directory patterns, `*`, `?`, bracket classes/ranges, escaped characters, and the
 * three boundary-positioned `**` forms. File links are deliberately excluded: an atomic replace
 * would replace the link rather than its target. Directory links are followed only inside the
 * resolved root and only when they do not create an ancestor cycle.
 */
internal object ProjectFileDiscovery {
    private val logger = BossLogger.forComponent("ProjectFileDiscovery")

    private val defaultExcludedDirectories =
        setOf(
            ".git",
            ".hg",
            ".svn",
            ".idea",
            ".gradle",
            ".cache",
            ".build",
            "build",
            "out",
            "target",
            "dist",
            "coverage",
            "node_modules",
            "vendor",
            "__pycache__",
            ".next",
            ".nuxt",
            ".venv",
            "venv",
            "env",
            ".env",
        )

    /** [acceptFile] is applied before the file budget, so filtered searches are not starved. */
    @Suppress("ReturnCount") // Each early return turns an unsafe partial walk into an explicit incomplete result.
    suspend fun discover(
        projectPath: String,
        acceptFile: (String) -> Boolean = { true },
    ): ProjectDiscoveryResult {
        val root = resolveRoot(projectPath) ?: return ProjectDiscoveryResult(emptyList())
        val files = mutableListOf<ProjectFile>()
        val pending = ArrayDeque<DirectoryWork>()
        pending += DirectoryWork(root.absolutePath, emptyList(), emptyList(), setOf(root.realPath))
        var visitedDirectories = 0

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++visitedDirectories > MAX_DIRECTORIES) return incomplete("directory", projectPath, files)
            val work = pending.removeLast()
            val ignoreRules = readIgnoreRules(work.path, work.relativePath)
            if (ignoreRules.incomplete) return incomplete(".gitignore", projectPath, files)
            val rules = work.rules + ignoreRules.rules
            val visit = visitDirectory(work, root, rules, pending, files, acceptFile)
            if (visit != null) return incomplete(visit, projectPath, files)
        }
        return ProjectDiscoveryResult(files)
    }

    @Suppress(
        "LongParameterList",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "LoopWithTooManyJumpStatements",
    ) // Keep confinement, ignore, cancellation, and budget decisions together at the directory boundary.
    private suspend fun visitDirectory(
        work: DirectoryWork,
        root: ProjectRoot,
        rules: List<IgnoreRule>,
        pending: ArrayDeque<DirectoryWork>,
        files: MutableList<ProjectFile>,
        acceptFile: (String) -> Boolean,
    ): String? =
        try {
            Files.newDirectoryStream(work.path).use { children ->
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    val name = child.fileName?.toString() ?: continue
                    val relative = work.relativePath + name
                    val kind = classify(child, root.realPath) ?: continue
                    if (kind.directory && name in defaultExcludedDirectories) continue
                    if (isIgnored(relative, kind.directory, rules)) continue
                    when {
                        kind.directory -> {
                            val real = kind.realPath ?: continue
                            if (real in work.ancestorRealPaths) {
                                logger.debug(
                                    LogCategory.FILE,
                                    "Skipping project directory link cycle",
                                    mapOf("path" to child.toString()),
                                )
                            } else {
                                pending += DirectoryWork(child, relative, rules, work.ancestorRealPaths + real)
                            }
                        }

                        kind.regularFile -> {
                            val relativeText = relative.joinToString(File.separator)
                            if (!acceptFile(relativeText.replace('\\', '/'))) continue
                            if (files.size >= MAX_FILES) return "file"
                            files += ProjectFile(child.toFile(), relativeText)
                        }
                    }
                }
            }
            null
        } catch (e: java.io.IOException) {
            logger.debug(
                LogCategory.FILE,
                "Skipping unreadable project directory",
                mapOf(
                    "path" to work.path.toString(),
                    "error" to e.toString(),
                ),
            )
            null
        }

    private fun incomplete(
        kind: String,
        projectPath: String,
        files: List<ProjectFile>,
    ): ProjectDiscoveryResult {
        val reason = "Project discovery reached its $kind budget; results are incomplete"
        logger.warn(
            LogCategory.FILE,
            reason,
            mapOf(
                "path" to projectPath,
                "directoryBudget" to MAX_DIRECTORIES,
                "fileBudget" to MAX_FILES,
            ),
        )
        return ProjectDiscoveryResult(files, reason)
    }

    private fun resolveRoot(projectPath: String): ProjectRoot? {
        val absolute = File(projectPath).toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(absolute)) return null
        return runCatching { ProjectRoot(absolute, absolute.toRealPath()) }.getOrNull()
    }

    @Suppress("ReturnCount") // Every early return rejects an unsafe or unreadable filesystem entry.
    private fun classify(
        path: Path,
        rootRealPath: Path,
    ): EntryKind? {
        val noFollow =
            runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull() ?: return null
        val link = noFollow.isSymbolicLink || noFollow.isOther
        val resolved = if (link) runCatching { path.toRealPath() }.getOrNull() else null
        if (link && (resolved == null || !resolved.startsWith(rootRealPath))) return null
        val followed =
            (
                if (resolved != null) {
                    runCatching { Files.readAttributes(resolved, BasicFileAttributes::class.java) }.getOrNull()
                } else {
                    noFollow
                }
            ) ?: return null
        return EntryKind(
            directory = followed.isDirectory,
            regularFile = !link && followed.isRegularFile,
            realPath = if (followed.isDirectory) resolved ?: runCatching { path.toRealPath() }.getOrNull() else null,
        )
    }

    // Streaming keeps cancellation and the size bound inside the read loop.
    @Suppress("ReturnCount", "NestedBlockDepth")
    private suspend fun readIgnoreRules(
        directory: Path,
        basePath: List<String>,
    ): IgnoreRead {
        val ignoreFile = directory.resolve(".gitignore")
        if (
            !Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(ignoreFile)
        ) {
            return IgnoreRead(emptyList())
        }
        return try {
            Files.newBufferedReader(ignoreFile).use { reader ->
                val rules = mutableListOf<IgnoreRule>()
                val line = StringBuilder()
                var chars = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = reader.read()
                    if (next == -1 || next == '\n'.code) {
                        IgnoreRule.parse(line.toString(), basePath)?.let(rules::add)
                        line.clear()
                        if (next == -1) break
                    } else {
                        if (++chars > MAX_IGNORE_CHARS) return IgnoreRead(rules, incomplete = true)
                        line.append(next.toChar())
                    }
                }
                IgnoreRead(rules)
            }
        } catch (e: java.io.IOException) {
            logger.debug(
                LogCategory.FILE,
                "Could not read project gitignore",
                mapOf(
                    "path" to ignoreFile.toString(),
                    "error" to e.toString(),
                ),
            )
            IgnoreRead(emptyList())
        }
    }

    private fun isIgnored(
        relativePath: List<String>,
        isDirectory: Boolean,
        rules: List<IgnoreRule>,
    ): Boolean {
        var ignored = false
        for (rule in rules) if (rule.matches(relativePath, isDirectory)) ignored = !rule.negated
        return ignored
    }

    private data class ProjectRoot(
        val absolutePath: Path,
        val realPath: Path,
    )

    private data class DirectoryWork(
        val path: Path,
        val relativePath: List<String>,
        val rules: List<IgnoreRule>,
        val ancestorRealPaths: Set<Path>,
    )

    private data class EntryKind(
        val directory: Boolean,
        val regularFile: Boolean,
        val realPath: Path?,
    )

    private data class IgnoreRead(
        val rules: List<IgnoreRule>,
        val incomplete: Boolean = false,
    )

    private data class IgnoreRule(
        val basePath: List<String>,
        val negated: Boolean,
        val directoryOnly: Boolean,
        val pathScoped: Boolean,
        val matcher: Regex,
    ) {
        @Suppress("ReturnCount") // Fast rejection avoids allocating a candidate for rules that cannot apply.
        fun matches(
            relativePath: List<String>,
            isDirectory: Boolean,
        ): Boolean {
            if (directoryOnly && !isDirectory) return false
            if (relativePath.size <= basePath.size || relativePath.take(basePath.size) != basePath) return false
            val beneath = relativePath.drop(basePath.size)
            return matcher.matches(if (pathScoped) beneath.joinToString("/") else beneath.last())
        }

        companion object {
            @Suppress("ReturnCount") // Invalid and comment lines are discarded as soon as their syntax is known.
            fun parse(
                raw: String,
                basePath: List<String>,
            ): IgnoreRule? {
                var line = raw.removeSuffix("\r").trimTrailingUnescapedSpaces()
                if (line.isEmpty() || line.startsWith('#')) return null
                val escapedLeading = line.startsWith("\\#") || line.startsWith("\\!")
                if (escapedLeading) line = line.drop(1)
                val negated = !escapedLeading && line.startsWith('!')
                if (negated) line = line.drop(1)
                val directoryOnly = line.endsWith('/')
                if (directoryOnly) line = line.dropLast(1)
                val anchored = line.startsWith('/')
                if (anchored) line = line.drop(1)
                if (line.isEmpty() || line.endsWith('\\')) return null
                return IgnoreRule(basePath, negated, directoryOnly, anchored || '/' in line, gitGlobRegex(line))
            }

            @Suppress("ComplexCondition") // All clauses describe one trailing, unescaped space.
            private fun String.trimTrailingUnescapedSpaces(): String {
                var end = length
                while (end > 0 && this[end - 1] == ' ' && (end < 2 || this[end - 2] != '\\')) end--
                return substring(0, end)
            }

            @Suppress("CyclomaticComplexMethod") // Each branch is one distinct piece of Git's glob grammar.
            private fun gitGlobRegex(pattern: String): Regex {
                val regex = StringBuilder()
                var i = 0
                while (i < pattern.length) {
                    when {
                        i == 0 && pattern.startsWith("**/", i) -> {
                            regex.append("(?:[^/]+/)*")
                            i += 3
                        }

                        pattern.startsWith("/**/", i) -> {
                            regex.append("(?:/[^/]+)*/")
                            i += 4
                        }

                        i + 3 == pattern.length && pattern.startsWith("/**", i) -> {
                            regex.append("/.*")
                            i += 3
                        }

                        pattern[i] == '\\' && i + 1 < pattern.length -> {
                            regex.append(Regex.escape(pattern[i + 1].toString()))
                            i += 2
                        }

                        pattern[i] == '*' -> {
                            regex.append("[^/]*")
                            i++
                        }

                        pattern[i] == '?' -> {
                            regex.append("[^/]")
                            i++
                        }

                        pattern[i] == '[' -> {
                            val characterClass = gitCharacterClass(pattern, i)
                            if (characterClass == null) {
                                regex.append("\\[")
                                i++
                            } else {
                                regex.append(characterClass.regex)
                                i = characterClass.nextIndex
                            }
                        }

                        else -> {
                            regex.append(Regex.escape(pattern[i].toString()))
                            i++
                        }
                    }
                }
                return Regex(regex.toString())
            }

            /**
             * Converts a Git bracket class into Java-regex syntax without copying its members
             * directly. `[` and `]` are valid Git class members (`[[]`, `[]]`) but need escaping
             * in Java, where copying them can create an invalid pattern and abort discovery.
             */
            private fun gitCharacterClass(
                pattern: String,
                start: Int,
            ): CharacterClass? {
                var end = start + 1
                if (end < pattern.length && pattern[end] == '!') end++
                if (end < pattern.length && pattern[end] == ']') end++
                var escaped = false
                while (end < pattern.length) {
                    val character = pattern[end]
                    if (!escaped && character == ']') break
                    escaped = !escaped && character == '\\'
                    if (character != '\\') escaped = false
                    end++
                }
                if (end == pattern.length) return null

                val rawMembers = pattern.substring(start + 1, end)
                val negated = rawMembers.startsWith('!')
                val members = if (negated) rawMembers.drop(1) else rawMembers
                return CharacterClass(
                    regex = "[${if (negated) "^" else ""}${javaClassMembers(members)}]",
                    nextIndex = end + 1,
                )
            }

            private fun javaClassMembers(members: String): String {
                val regex = StringBuilder()
                var index = 0
                while (index < members.length) {
                    val character = members[index]
                    if (character == '\\' && index + 1 < members.length) {
                        regex.append("\\Q").append(members[index + 1]).append("\\E")
                        index += 2
                    } else {
                        if (character in "[]^\\") regex.append('\\')
                        regex.append(character)
                        index++
                    }
                }
                return regex.toString()
            }

            private data class CharacterClass(
                val regex: String,
                val nextIndex: Int,
            )
        }
    }

    private const val MAX_DIRECTORIES = 25_000
    private const val MAX_FILES = 50_000
    private const val MAX_IGNORE_CHARS = 1_048_576
}
