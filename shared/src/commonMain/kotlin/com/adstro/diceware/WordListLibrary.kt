package com.adstro.diceware

import io.ktor.client.engine.HttpClientEngine
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
import okio.Path.Companion.toPath
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

/** A Custom Word List, or with [eff] set, an updated copy of that EFF Word List from eff.org. */
@Serializable
private data class IndexEntry(val sha256: String, val name: String, val dicePerRoll: Int, val eff: EffWordList? = null)

private const val INDEX_FILE = "index.json"
private const val STAGING_DIR = "importing"
private const val EFF_STAGING_DIR = "eff-update"
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
    private val httpEngine: () -> HttpClientEngine = ::platformHttpEngine,
) {
    // Made on the first check, so there is no network client until the user asks for one.
    private val http by lazy { httpClient(httpEngine()) }
    private var eff: Map<EffWordList, WordList> = emptyMap()
    private val entries = MutableStateFlow<List<IndexEntry>>(emptyList())
    private val custom = MutableStateFlow<Map<CustomWordListId, DiskWordList>>(emptyMap())
    private val effCopies = MutableStateFlow<Map<EffWordList, DiskWordList>>(emptyMap())
    private val indexLock = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var importJob: Job? = null

    private val mutableWordLists = MutableStateFlow<List<WordListInfo>>(emptyList())

    /** Word Lists screen state: every Word List in the order shown, EFF first; empty until loaded. */
    val wordLists: StateFlow<List<WordListInfo>> = mutableWordLists.asStateFlow()

    private val mutableImportState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = mutableImportState.asStateFlow()

    private val mutableEffUpdateState = MutableStateFlow<EffUpdateState>(EffUpdateState.Idle)
    val effUpdateState: StateFlow<EffUpdateState> = mutableEffUpdateState.asStateFlow()

    /** The downloaded EFF Word Lists waiting in [EFF_STAGING_DIR] for the user to apply them. */
    private var pendingEff: List<IndexEntry> = emptyList()

    init {
        scope.launch {
            withContext(ioContext) {
                eff = bundled()
                fileSystem.createDirectories(directory)
                fileSystem.deleteRecursively(directory / STAGING_DIR)
                fileSystem.deleteRecursively(directory / EFF_STAGING_DIR)
                val saved = readIndex()
                custom.value = saved.filter { it.eff == null }.associate { CustomWordListId(it.sha256) to openList(it) }
                effCopies.value = saved.mapNotNull { entry -> entry.eff?.let { it to openList(entry) } }.toMap()
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
        is EffWordList -> effCopies.value[id] ?: eff[id]
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
                    EffWordList.entries.firstOrNull { effSha256(it) == staged.sha256 }?.let {
                        throw WordListRejected(ImportError.AlreadyImportedAsEff(it))
                    }
                    entries.value.firstOrNull { it.eff == null && it.sha256 == staged.sha256 }?.let {
                        throw WordListRejected(ImportError.AlreadyImported(it.name))
                    }
                    for (file in listOf(WORDS_FILE, OFFSETS_FILE)) moveIn(staging / file, "${staged.sha256}.$file")
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

    /** [import] of the file at [path], for iOS, which hands the app file URLs. */
    suspend fun importFile(path: String, fileName: String) {
        val file = path.toPath()
        import(fileName, fileSystem.metadataOrNull(file)?.size ?: -1) { fileSystem.source(file) }
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
        changeIndex { list -> list.map { if (it.eff == null && it.sha256 == id.sha256) it.copy(name = newName) else it } }
    }

    /** Deletes a Custom Word List; if it was the Selected Word List the Generator falls back to EFF Large. */
    fun delete(id: CustomWordListId) {
        // Closed on the caller's thread, the one that rolls, so no Roll can be reading it.
        custom.getAndUpdate { it - id }[id]?.close()
        changeIndex { list -> list.filterNot { it.eff == null && it.sha256 == id.sha256 } }
    }

    /**
     * Downloads the EFF Word Lists from eff.org, validating each like an import, and compares them with the
     * ones in use, updating [effUpdateState]; returns once done. Only ever called when the user asks.
     */
    suspend fun checkForEffUpdates() {
        if (mutableEffUpdateState.value == EffUpdateState.Checking) return
        mutableEffUpdateState.value = EffUpdateState.Checking
        pendingEff = emptyList()
        val staging = directory / EFF_STAGING_DIR
        mutableEffUpdateState.value = withContext(ioContext) {
            try {
                awaitLoaded()
                fileSystem.deleteRecursively(staging)
                val pending = mutableListOf<IndexEntry>()
                val changes = EffWordList.entries.mapNotNull { list ->
                    val body = http.download(list.url)
                    val listStaging = staging / list.name
                    fileSystem.createDirectories(listStaging)
                    val staged = try {
                        stageWordList(body, body.size, fileSystem, listStaging) {}
                    } catch (e: WordListRejected) {
                        throw EffUpdateFailed(EffUpdateError.Invalid(list, e.error))
                    }
                    val current = wordList(list)!!
                    if (staged.sha256 == current.sha256()) return@mapNotNull null
                    for (file in listOf(WORDS_FILE, OFFSETS_FILE)) fileSystem.atomicMove(listStaging / file, staging / "${staged.sha256}.$file")
                    pending += IndexEntry(staged.sha256, "", staged.dicePerRoll, list)
                    val downloaded = DiskWordList(staged.dicePerRoll, fileSystem, staging, staged.sha256)
                    try { change(list, current, downloaded) } finally { downloaded.close() }
                }
                pendingEff = pending
                if (changes.isEmpty()) EffUpdateState.UpToDate else EffUpdateState.UpdateAvailable(changes)
            } catch (e: EffUpdateFailed) {
                EffUpdateState.Failed(e.error)
            } catch (e: IOException) {
                EffUpdateState.Failed(EffUpdateError.StorageFailed)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { fileSystem.deleteRecursively(staging) } }
                mutableEffUpdateState.value = EffUpdateState.Idle
                throw e
            }.also { if (it !is EffUpdateState.UpdateAvailable) runCatching { fileSystem.deleteRecursively(staging) } }
        }
    }

    /** Replaces the EFF Word Lists the last check found changed with the downloaded ones. */
    fun applyEffUpdate() {
        if (mutableEffUpdateState.value !is EffUpdateState.UpdateAvailable) return
        val pending = pendingEff
        pendingEff = emptyList()
        scope.launch {
            mutableEffUpdateState.value = withContext(ioContext) {
                val staging = directory / EFF_STAGING_DIR
                try {
                    indexLock.withLock {
                        // eff.org serving the bundled Words again just drops the copy.
                        val copies = pending.filter { it.sha256 != eff.getValue(it.eff!!).sha256() }
                        for (entry in copies) for (file in listOf(WORDS_FILE, OFFSETS_FILE)) moveIn(staging / "${entry.sha256}.$file", "${entry.sha256}.$file")
                        val replaced = pending.mapNotNull { it.eff }.toSet()
                        val updated = entries.value.filterNot { it.eff in replaced } + copies
                        writeIndex(updated)
                        // shortcut: a replaced earlier copy is not closed, as a Roll on the main thread may be reading it;
                        // one leaked handle per update of an update, close it from the main thread if that ever matters.
                        effCopies.update { old -> old - replaced + copies.associate { it.eff!! to openList(it) } }
                        entries.value = updated
                        deleteFilesNotIn(updated)
                    }
                    EffUpdateState.Updated
                } catch (e: IOException) {
                    EffUpdateState.Failed(EffUpdateError.StorageFailed)
                } finally {
                    runCatching { fileSystem.deleteRecursively(staging) }
                }
            }
            publish()
        }
    }

    /**
     * Back to [EffUpdateState.Idle], dropping an update the user did not apply.
     * Its downloaded files stay staged (out of use) until the next check or launch clears them.
     */
    fun dismissEffUpdate() {
        if (mutableEffUpdateState.value == EffUpdateState.Checking) return
        mutableEffUpdateState.value = EffUpdateState.Idle
        pendingEff = emptyList()
    }

    /** Goes back to the EFF Word Lists bundled with the app, removing any updated copies. */
    fun restoreBundledEffWordLists() {
        // Closed on the caller's thread, the one that rolls, so no Roll can be reading them.
        effCopies.getAndUpdate { emptyMap() }.values.forEach { it.close() }
        changeIndex { list -> list.filter { it.eff == null } }
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

    // Hashed on first import, off the main thread; bundled lists never change while the app runs.
    private val bundledSha256 by lazy { eff.mapValues { it.value.sha256() } }

    /** The SHA-256 of the EFF Word List in use: an updated copy's, or the bundled one's. */
    private fun effSha256(list: EffWordList): String =
        entries.value.firstOrNull { it.eff == list }?.sha256 ?: bundledSha256.getValue(list)

    private fun publish() {
        val copies = effCopies.value
        mutableWordLists.value = EffWordList.entries.map { wordListInfo(it, null, (copies[it] ?: eff.getValue(it)).dicePerRoll, it in copies) } +
            entries.value.filter { it.eff == null }.map { wordListInfo(CustomWordListId(it.sha256), it.name, it.dicePerRoll) }
    }

    /** Moves a validated file in; one with the same name already holds the same Words, so it is kept. */
    private fun moveIn(file: Path, name: String) {
        if (!fileSystem.exists(directory / name)) fileSystem.atomicMove(file, directory / name)
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
        fileSystem.list(directory).filter { it.name !in keep && it.name != STAGING_DIR && it.name != EFF_STAGING_DIR }.forEach { runCatching { fileSystem.delete(it) } }
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
