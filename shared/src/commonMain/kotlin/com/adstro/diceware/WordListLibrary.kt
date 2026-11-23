package com.adstro.diceware

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.ForwardingSource
import okio.IOException
import okio.Path
import okio.Source
import okio.buffer
import okio.use
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** Word Lists screen state for an import. */
sealed interface ImportState {
    data object Idle : ImportState

    /** [progress] runs from 0 to 1. */
    data class Importing(val progress: Double) : ImportState

    data class Imported(val name: String) : ImportState

    data class Failed(val error: ImportError) : ImportState
}

@Serializable
private data class IndexEntry(val sha256: String, val name: String, val dicePerRoll: Int)

private const val INDEX_FILE = "index.json"
private const val STAGING_DIR = "importing"
private const val MAX_NAME_LENGTH = 100

/**
 * Every Word List: the bundled EFF Word Lists plus Custom Word Lists imported into [directory], which the apps
 * keep app-private and out of cloud backup. Disk work runs on [ioContext], never the caller's thread.
 */
class WordListLibrary(
    private val bundled: () -> Map<EffWordList, WordList>,
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private var eff: Map<EffWordList, WordList> = emptyMap()
    private val entries = MutableStateFlow<List<IndexEntry>>(emptyList())
    private val custom = MutableStateFlow<Map<CustomWordListId, DiskWordList>>(emptyMap())
    private val indexLock = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var importJob: Job? = null

    private val mutableWordLists = MutableStateFlow<List<WordListInfo>>(emptyList())

    /** Word Lists screen state: every Word List in the order shown, EFF first; empty until loaded. */
    val wordLists: StateFlow<List<WordListInfo>> = mutableWordLists.asStateFlow()

    private val mutableImportState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = mutableImportState.asStateFlow()

    init {
        scope.launch {
            withContext(ioContext) {
                eff = bundled()
                fileSystem.createDirectories(directory)
                fileSystem.deleteRecursively(directory / STAGING_DIR)
                val saved = readIndex()
                custom.value = saved.associate { CustomWordListId(it.sha256) to openList(it) }
                entries.value = saved
                deleteFilesNotIn(saved)
            }
            publish()
            loaded.complete(Unit)
        }
    }

    internal suspend fun awaitLoaded() = loaded.await()

    /** The Word List to resolve Rolls against, or null if there is no such list (any more). */
    fun wordList(id: WordListId): WordList? = when (id) {
        is EffWordList -> eff[id]
        is CustomWordListId -> custom.value[id]
    }

    /**
     * Imports a Custom Word List named after [fileName] from the untrusted [open]ed source of [sizeBytes]
     * (≤ 0 if unknown), updating [importState]; returns once it is imported, rejected or cancelled.
     * Ignored while another import is running.
     */
    suspend fun import(fileName: String, sizeBytes: Long, open: () -> Source) {
        if (importJob?.isActive == true) return
        mutableImportState.value = ImportState.Importing(0.0)
        val job = scope.launch(ioContext) {
            val staging = directory / STAGING_DIR
            try {
                awaitLoaded()
                fileSystem.deleteRecursively(staging)
                fileSystem.createDirectories(staging)
                val input = try {
                    open()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw WordListRejected(ImportError.Unreadable)
                }
                val staged = UnreadableSource(input).buffer().use { source ->
                    stageWordList(source, sizeBytes, fileSystem, staging) { mutableImportState.value = ImportState.Importing(it) }
                }
                val name = nameOf(fileName)
                indexLock.withLock {
                    entries.value.firstOrNull { it.sha256 == staged.sha256 }?.let {
                        throw WordListRejected(ImportError.AlreadyImported(it.name))
                    }
                    for (file in listOf(WORDS_FILE, OFFSETS_FILE)) {
                        fileSystem.atomicMove(staging / file, directory / "${staged.sha256}.$file")
                    }
                    val entry = IndexEntry(staged.sha256, name, staged.dicePerRoll)
                    writeIndex(entries.value + entry)
                    custom.update { it + (CustomWordListId(entry.sha256) to openList(entry)) }
                    entries.update { it + entry }
                }
                publish()
                mutableImportState.value = ImportState.Imported(name)
            } catch (e: WordListRejected) {
                mutableImportState.value = ImportState.Failed(e.error)
            } catch (e: IOException) {
                mutableImportState.value = ImportState.Failed(ImportError.StorageFailed)
            } catch (e: CancellationException) {
                mutableImportState.value = ImportState.Idle
                throw e
            } finally {
                withContext(NonCancellable) { runCatching { fileSystem.deleteRecursively(staging) } }
            }
        }
        importJob = job
        job.join()
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /** Back to [ImportState.Idle] once the user has seen an import's result. */
    fun dismissImportResult() {
        mutableImportState.update { if (it is ImportState.Importing) it else ImportState.Idle }
    }

    /** Renames a Custom Word List; a blank name is ignored. EFF Word Lists can't be renamed. */
    fun rename(id: CustomWordListId, name: String) {
        val newName = name.trim().take(MAX_NAME_LENGTH)
        if (newName.isEmpty()) return
        changeIndex { list -> list.map { if (it.sha256 == id.sha256) it.copy(name = newName) else it } }
    }

    /** Deletes a Custom Word List; if it was the Selected Word List the Generator falls back to EFF Large. */
    fun delete(id: CustomWordListId) {
        // Closed on the caller's thread, the one that rolls, so no Roll can be reading it.
        custom.getAndUpdate { it - id }[id]?.close()
        changeIndex { list -> list.filterNot { it.sha256 == id.sha256 } }
    }

    private fun changeIndex(change: (List<IndexEntry>) -> List<IndexEntry>) {
        scope.launch {
            withContext(ioContext) {
                awaitLoaded()
                indexLock.withLock {
                    val updated = change(entries.value)
                    writeIndex(updated)
                    entries.value = updated
                    deleteFilesNotIn(updated)
                }
            }
            publish()
        }
    }

    private fun publish() {
        mutableWordLists.value = EffWordList.entries.map { wordListInfo(it, null, eff.getValue(it).dicePerRoll) } +
            entries.value.map { wordListInfo(CustomWordListId(it.sha256), it.name, it.dicePerRoll) }
    }

    private fun openList(entry: IndexEntry) = DiskWordList(entry.dicePerRoll, fileSystem, directory, entry.sha256)

    /** The saved index is app-private but still read defensively: a damaged index lists no Custom Word Lists. */
    private fun readIndex(): List<IndexEntry> = runCatching {
        Json.decodeFromString<List<IndexEntry>>(fileSystem.read(directory / INDEX_FILE) { readUtf8() })
            .filter { fileSystem.exists(directory / "${it.sha256}.$WORDS_FILE") && fileSystem.exists(directory / "${it.sha256}.$OFFSETS_FILE") }
    }.getOrDefault(emptyList())

    private fun writeIndex(list: List<IndexEntry>) {
        val temporary = directory / "$INDEX_FILE.tmp"
        fileSystem.write(temporary) { writeUtf8(Json.encodeToString(list)) }
        fileSystem.atomicMove(temporary, directory / INDEX_FILE)
    }

    /** Removes Word List files a deletion or interrupted import left behind. */
    private fun deleteFilesNotIn(list: List<IndexEntry>) {
        val keep = list.flatMap { listOf("${it.sha256}.$WORDS_FILE", "${it.sha256}.$OFFSETS_FILE") }.toSet() + INDEX_FILE
        // Best effort: whatever can't be deleted now is retried at the next launch.
        fileSystem.list(directory).filter { it.name !in keep && it.name != STAGING_DIR }.forEach { runCatching { fileSystem.delete(it) } }
    }
}

/** The file name without its extension, as typed by whoever made the file. */
private fun nameOf(fileName: String): String {
    val base = fileName.substringAfterLast('/').trim()
    val name = (if ('.' in base.drop(1)) base.substringBeforeLast('.') else base).trim()
    return name.ifEmpty { base }.ifEmpty { "Word List" }.take(MAX_NAME_LENGTH)
}

/** Read failures of the imported file are the file's fault, not a lack of storage space. */
private class UnreadableSource(delegate: Source) : ForwardingSource(delegate) {
    override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
        super.read(sink, byteCount)
    } catch (e: IOException) {
        throw WordListRejected(ImportError.Unreadable)
    }
}
