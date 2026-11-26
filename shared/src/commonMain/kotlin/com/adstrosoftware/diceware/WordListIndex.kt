package com.adstrosoftware.diceware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** One saved Word List: a Custom Word List, or an updated copy of an EFF Word List from eff.org. */
internal sealed interface IndexEntry {
    val sha256: String
    val dicePerRoll: Int

    data class Custom(override val sha256: String, val name: String, override val dicePerRoll: Int) : IndexEntry

    data class EffCopy(override val sha256: String, override val dicePerRoll: Int, val wordList: EffWordList) :
        IndexEntry
}

/** An [IndexEntry] as `index.json` has always stored it: [eff] is set only for an EFF Word List copy. */
@Serializable
private data class SavedEntry(
    val sha256: String,
    val name: String,
    val dicePerRoll: Int,
    val eff: EffWordList? = null,
) {
    fun toEntry(): IndexEntry =
        eff?.let { IndexEntry.EffCopy(sha256, dicePerRoll, it) } ?: IndexEntry.Custom(sha256, name, dicePerRoll)
}

private fun IndexEntry.toSaved() = when (this) {
    is IndexEntry.Custom -> SavedEntry(sha256, name, dicePerRoll)
    is IndexEntry.EffCopy -> SavedEntry(sha256, "", dicePerRoll, wordList)
}

internal const val INDEX_FILE = "index.json"
internal const val STAGING_DIR = "importing"
internal const val EFF_STAGING_DIR = "eff-update"

/** The files [stageWordList] writes for every Word List. */
internal val WORD_LIST_FILES = listOf(WORDS_FILE, OFFSETS_FILE)

/** The name a saved Word List's [file] (one of [WORD_LIST_FILES]) has: `<sha256>.<file>`. */
internal fun wordListFileName(sha256: String, file: String) = "$sha256.$file"

/**
 * The Word Lists saved in [directory]: `index.json` naming them, beside the files of each. Change it only
 * under [lock]; the index is replaced atomically, so it never names files that aren't there.
 */
internal class WordListIndex(val fileSystem: FileSystem, val directory: Path) {
    val lock = Mutex()
    val entries = MutableStateFlow<List<IndexEntry>>(emptyList())

    /** Reads the saved index, clearing whatever an interrupted import, check or deletion left behind. */
    fun load(): List<IndexEntry> {
        fileSystem.createDirectories(directory)
        fileSystem.deleteRecursively(directory / STAGING_DIR)
        fileSystem.deleteRecursively(directory / EFF_STAGING_DIR)
        val saved = read()
        entries.value = saved
        deleteFilesNotIn(saved)
        return saved
    }

    /** Saves [list] as the index, deleting the files of Word Lists no longer in it. */
    fun save(list: List<IndexEntry>) {
        val temporary = directory / "$INDEX_FILE.tmp"
        fileSystem.write(temporary) { writeUtf8(Json.encodeToString(list.map { it.toSaved() })) }
        fileSystem.atomicMove(temporary, directory / INDEX_FILE)
        entries.value = list
        deleteFilesNotIn(list)
    }

    /** Moves a validated file in; one with the same name already holds the same Words, so it is kept. */
    fun moveIn(file: Path, name: String) {
        if (!fileSystem.exists(directory / name)) fileSystem.atomicMove(file, directory / name)
    }

    fun open(entry: IndexEntry) = DiskWordList(entry.dicePerRoll, fileSystem, directory, entry.sha256)

    /** The saved index is app-private but still read defensively: a damaged index lists no Custom Word Lists. */
    private fun read(): List<IndexEntry> = runCatching {
        Json.decodeFromString<List<SavedEntry>>(fileSystem.read(directory / INDEX_FILE) { readUtf8() })
            .map { it.toEntry() }
            .filter { entry ->
                WORD_LIST_FILES.all { fileSystem.exists(directory / wordListFileName(entry.sha256, it)) }
            }
    }.getOrDefault(emptyList())

    /** Removes Word List files a deletion or interrupted import left behind. */
    private fun deleteFilesNotIn(list: List<IndexEntry>) {
        val keep = list.flatMap { entry -> WORD_LIST_FILES.map { wordListFileName(entry.sha256, it) } }.toSet() +
            INDEX_FILE
        // Best effort: whatever can't be deleted now is retried at the next launch.
        fileSystem.list(directory)
            .filter { it.name !in keep && it.name != STAGING_DIR && it.name != EFF_STAGING_DIR }
            .forEach { runCatching { fileSystem.delete(it) } }
    }
}
