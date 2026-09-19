package ai.rever.boss.downloads

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent history of completed downloads, in `~/.boss/download-history.json`.
 *
 * The browser's `DownloadManager` tracks live progress in memory and forgets everything on
 * restart; this records each completed download durably so the operator (and an agent through
 * `DownloadHistoryMcpToolProvider`) can see what was downloaded across sessions.
 *
 * The persistence contract is the one every small BOSS state file follows: atomic writes via
 * [atomicWriteText], mutations serialized under [mutex], forward-coercing reads
 * (`ignoreUnknownKeys`), and a bound of [MAX_ENTRIES] newest records. [storageFile] is an
 * overridable test hook, mirroring `RunConfigurationManager`.
 */
object DownloadHistoryManager {
    private val logger = BossLogger.forComponent("DownloadHistoryManager")

    /** Newest records kept; older ones drop off on the next record. */
    const val MAX_ENTRIES = 500

    private val defaultStorageFile = BossDirectories.resolve("download-history.json")

    @Volatile
    internal var storageFile: File = defaultStorageFile

    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val mutex = Mutex()

    private val _downloads = MutableStateFlow<List<DownloadRecord>>(emptyList())

    /** Newest first. */
    val downloads: StateFlow<List<DownloadRecord>> = _downloads.asStateFlow()

    init {
        storageFile.parentFile?.mkdirs()
        loadSync()
    }

    internal fun loadSync() {
        try {
            if (storageFile.exists()) {
                val history = json.decodeFromString(DownloadHistory.serializer(), storageFile.readText())
                _downloads.value = history.downloads
            } else {
                _downloads.value = emptyList()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.SYSTEM, "Failed to load download history", error = e)
            _downloads.value = emptyList()
        }
    }

    internal fun resetForTesting(testFile: File? = null) {
        storageFile = testFile ?: defaultStorageFile
        clock = { System.currentTimeMillis() }
        loadSync()
    }

    /**
     * Record a completed download of [url] saved to [filePath]. The file name is derived from the
     * path. Returns the stored record.
     */
    suspend fun record(
        url: String,
        filePath: String,
        sizeBytes: Long? = null,
    ): DownloadRecord =
        mutex.withLock {
            val record =
                DownloadRecord(
                    id = "download-${clock()}-${(0..9999).random()}",
                    url = url,
                    fileName = File(filePath).name,
                    filePath = filePath,
                    sizeBytes = sizeBytes,
                    completedAt = clock(),
                )
            val updated = (listOf(record) + _downloads.value).take(MAX_ENTRIES)
            _downloads.value = updated
            persist(updated)
            record
        }

    /** Remove one record. Returns true when it existed. */
    suspend fun remove(id: String): Boolean =
        mutex.withLock {
            val current = _downloads.value
            val updated = current.filterNot { it.id == id }
            if (updated.size == current.size) {
                false
            } else {
                _downloads.value = updated
                persist(updated)
                true
            }
        }

    /** Remove every record. Returns the number removed. */
    suspend fun clear(): Int =
        mutex.withLock {
            val removed = _downloads.value.size
            if (removed == 0) return@withLock 0
            _downloads.value = emptyList()
            persist(emptyList())
            removed
        }

    private suspend fun persist(records: List<DownloadRecord>) =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(DownloadHistory.serializer(), DownloadHistory(records))
                storageFile.atomicWriteText(content)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(LogCategory.SYSTEM, "Failed to save download history", error = e)
            }
        }
}
