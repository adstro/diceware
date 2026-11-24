package com.adstrosoftware.diceware

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The EFF Word Lists in use: the bundled ones, or updated copies from eff.org saved in [index], and the
 * check of eff.org for those updates. Disk and network work runs on [ioContext]; [onChanged] republishes.
 */
internal class EffWordLists(
    private val index: WordListIndex,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext,
    httpEngine: () -> HttpClientEngine,
    private val awaitLoaded: suspend () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val fileSystem = index.fileSystem
    private val directory = index.directory

    // Made on the first check, so there is no network client until the user asks for one.
    private val http by lazy { httpClient(httpEngine()) }
    private var bundled: Map<EffWordList, WordList> = emptyMap()
    private val copies = MutableStateFlow<Map<EffWordList, DiskWordList>>(emptyMap())

    // Hashed on first use, off the main thread; bundled lists never change while the app runs.
    private val bundledSha256 by lazy { bundled.mapValues { it.value.sha256() } }

    private val mutableState = MutableStateFlow<EffUpdateState>(EffUpdateState.Idle)
    val state: StateFlow<EffUpdateState> = mutableState.asStateFlow()

    /** The downloaded EFF Word Lists waiting in [EFF_STAGING_DIR] for the user to apply them. */
    private var pending: List<IndexEntry.EffCopy> = emptyList()

    /** Called once, off the main thread, with the bundled lists and the saved index. */
    fun load(bundled: Map<EffWordList, WordList>, saved: List<IndexEntry>) {
        this.bundled = bundled
        copies.value = saved.filterIsInstance<IndexEntry.EffCopy>().associate { it.wordList to index.open(it) }
    }

    /** The EFF Word List to resolve Rolls against, or null until loaded. */
    fun inUse(list: EffWordList): WordList = copies.value[list] ?: bundled.getValue(list)

    /** The SHA-256 an import of the Words of [list] in use would give. */
    fun sha256InUse(list: EffWordList): String =
        index.entries.value.filterIsInstance<IndexEntry.EffCopy>().firstOrNull { it.wordList == list }?.sha256
            ?: bundledSha256.getValue(list)

    fun infos(): List<WordListInfo> =
        EffWordList.entries.map { wordListInfo(it, null, inUse(it).dicePerRoll, it in copies.value) }

    /**
     * Downloads the EFF Word Lists from eff.org, validating each like an import, and compares them with the
     * ones in use, updating [state]; returns once done. Only ever called when the user asks.
     */
    suspend fun check() {
        if (mutableState.value == EffUpdateState.Checking) return
        mutableState.value = EffUpdateState.Checking
        pending = emptyList()
        val staging = directory / EFF_STAGING_DIR
        mutableState.value = withContext(ioContext) {
            try {
                awaitLoaded()
                fileSystem.deleteRecursively(staging)
                val found = mutableListOf<IndexEntry.EffCopy>()
                val changes = EffWordList.entries.mapNotNull { list ->
                    val body = http.download(list.url)
                    val listStaging = staging / list.name
                    fileSystem.createDirectories(listStaging)
                    val staged = try {
                        stageWordList(body, body.size, fileSystem, listStaging) {}
                    } catch (e: WordListRejected) {
                        throw EffUpdateFailed(EffUpdateError.Invalid(list, e.error))
                    }
                    if (staged.sha256 == sha256InUse(list)) return@mapNotNull null
                    for (file in WORD_LIST_FILES) fileSystem.atomicMove(listStaging / file, staging / wordListFileName(staged.sha256, file))
                    found += IndexEntry.EffCopy(staged.sha256, staged.dicePerRoll, list)
                    val downloaded = DiskWordList(staged.dicePerRoll, fileSystem, staging, staged.sha256)
                    try { change(list, inUse(list), downloaded) } finally { downloaded.close() }
                }
                pending = found
                if (changes.isEmpty()) EffUpdateState.UpToDate else EffUpdateState.UpdateAvailable(changes)
            } catch (e: EffUpdateFailed) {
                EffUpdateState.Failed(e.error)
            } catch (e: IOException) {
                EffUpdateState.Failed(EffUpdateError.StorageFailed)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { fileSystem.deleteRecursively(staging) } }
                mutableState.value = EffUpdateState.Idle
                throw e
            }.also { if (it !is EffUpdateState.UpdateAvailable) runCatching { fileSystem.deleteRecursively(staging) } }
        }
    }

    /** Replaces the EFF Word Lists the last check found changed with the downloaded ones. */
    fun apply() {
        if (mutableState.value !is EffUpdateState.UpdateAvailable) return
        val toApply = pending
        pending = emptyList()
        scope.launch {
            mutableState.value = withContext(ioContext) {
                val staging = directory / EFF_STAGING_DIR
                try {
                    index.lock.withLock {
                        // eff.org serving the bundled Words again just drops the copy.
                        val newCopies = toApply.filter { it.sha256 != bundledSha256.getValue(it.wordList) }
                        for (entry in newCopies) {
                            for (file in WORD_LIST_FILES) {
                                val name = wordListFileName(entry.sha256, file)
                                index.moveIn(staging / name, name)
                            }
                        }
                        val replaced = toApply.map { it.wordList }.toSet()
                        index.save(index.entries.value.filterNot { it is IndexEntry.EffCopy && it.wordList in replaced } + newCopies)
                        // shortcut: a replaced earlier copy is not closed, as a Roll on the main thread may be reading it;
                        // one leaked handle per update of an update, close it from the main thread if that ever matters.
                        copies.update { old -> old - replaced + newCopies.associate { it.wordList to index.open(it) } }
                    }
                    EffUpdateState.Updated
                } catch (e: IOException) {
                    EffUpdateState.Failed(EffUpdateError.StorageFailed)
                } finally {
                    runCatching { fileSystem.deleteRecursively(staging) }
                }
            }
            onChanged()
        }
    }

    /**
     * Back to [EffUpdateState.Idle], dropping an update the user did not apply.
     * Its downloaded files stay staged (out of use) until the next check or launch clears them.
     */
    fun dismiss() {
        if (mutableState.value == EffUpdateState.Checking) return
        mutableState.value = EffUpdateState.Idle
        pending = emptyList()
    }

    /** Stops using the updated copies; the caller then removes them from the index. */
    fun closeCopies() {
        // Closed on the caller's thread, the one that rolls, so no Roll can be reading them.
        copies.getAndUpdate { emptyMap() }.values.forEach { it.close() }
    }
}
