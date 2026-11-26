package com.adstrosoftware.diceware

import kotlinx.coroutines.yield
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.buffer
import okio.use
import okio.utf8Size

/** Why an import was rejected; line numbers are 1-based lines of the imported file. Nothing is imported. */
/** What is wrong with one line of an imported file. */
enum class LineProblem {
    WORD_HAS_WHITESPACE,
    INVALID_ROLL,
    MIXED_ROLL_LENGTH,
    MISSING_ROLL,
    LINE_TOO_LONG,

    /** The file is not UTF-8 text. */
    NOT_TEXT,
}

sealed interface ImportError {
    /** Not exactly 6ⁿ Words with n ≥ 4. */
    data class WrongWordCount(val wordCount: Long) : ImportError
    data class DuplicateWord(val line: Long, val word: String, val firstLine: Long) : ImportError

    /** One line of the file is wrong in a way that needs only its number to explain. */
    data class BadLine(val line: Long, val problem: LineProblem) : ImportError

    /** A Diceware-format file with no line for this Roll. */
    data class RollNotFound(val roll: String) : ImportError
    data class RepeatedRoll(val line: Long, val firstLine: Long) : ImportError
    data class AlreadyImported(val name: String) : ImportError
    /** The file holds the same Words as this EFF Word List. */
    data class AlreadyImportedAsEff(val wordList: EffWordList) : ImportError
    data object Unreadable : ImportError
    /** Saving failed, most likely out of storage space. */
    data object StorageFailed : ImportError
}

internal class WordListRejected(val error: ImportError) : Exception(error.toString())

private fun reject(error: ImportError): Nothing = throw WordListRejected(error)

/** A validated Word List in [directory]: `words` in Roll order, one per line, and `offsets` locating each. */
internal class StagedWordList(val dicePerRoll: Int, val sha256: String)

internal const val WORDS_FILE = "words"
internal const val OFFSETS_FILE = "offsets"

private const val MAX_LINE_BYTES = 1024L
private const val RECORDS_PER_RUN = 1 shl 15
private const val YIELD_EVERY = 4096

/** The fewest dice per Roll a Word List may use: 6⁴ = 1,296 Words, the size of the EFF short lists. */
private const val MIN_DICE_PER_ROLL = 4

// Import progress: reading the file is the first half, then sorting by Roll and checking Words a quarter each.
private const val READ_SHARE = 0.5
private const val SORT_SHARE = 0.25

/**
 * Validates a Word List streamed from [source] into the empty [directory]; throws [WordListRejected] naming
 * the first offending line. Memory stays bounded whatever the size: Words are checked and put in Roll order
 * by external merge sorts on disk. [progress] gets 0–1.
 */
internal suspend fun stageWordList(
    source: BufferedSource,
    sizeBytes: Long,
    fileSystem: FileSystem,
    directory: Path,
    progress: (Double) -> Unit,
): StagedWordList {
    val byWord = ExternalSorter(fileSystem, directory / "by-word")
    val byRoll = ExternalSorter(fileSystem, directory / "by-roll")
    val output = WordListWriter(fileSystem, directory)
    try {
        val lines = LineChecker(byWord, byRoll, output)
        source.forEachLine(sizeBytes, progress, lines::add)
        val dicePerRoll = if (lines.isDiceware) {
            writeInRollOrder(byRoll, lines.rollLength, lines.count, output, progress)
        } else {
            exactLog6(lines.count)?.takeIf { it >= MIN_DICE_PER_ROLL }
                ?: reject(ImportError.WrongWordCount(lines.count))
        }
        checkNoDuplicateWords(byWord, lines.count, progress)
        return StagedWordList(dicePerRoll, output.finish())
    } finally {
        output.close()
        byWord.close()
        byRoll.close()
    }
}

/** Calls [each] with the number and trimmed text of every non-blank line; reading is the first half of [progress]. */
private suspend fun BufferedSource.forEachLine(
    sizeBytes: Long,
    progress: (Double) -> Unit,
    each: (Long, String) -> Unit,
) {
    var line = 0L
    var bytesRead = 0L
    while (true) {
        val text = readLineBounded(line + 1) ?: return
        line++
        bytesRead += text.utf8Size() + 1
        if (line % YIELD_EVERY == 0L) {
            yield()
            if (sizeBytes > 0) progress(READ_SHARE * bytesRead.coerceAtMost(sizeBytes) / sizeBytes)
        }
        // Malformed UTF-8 decodes to U+FFFD; importing it would silently change the Word.
        if ('\uFFFD' in text) reject(ImportError.BadLine(line, LineProblem.NOT_TEXT))
        val trimmed = (if (line == 1L) text.removePrefix("\uFEFF") else text).trim()
        if (trimmed.isNotEmpty()) each(line, trimmed)
    }
}

/** Checks each line's fields; the first line decides whether the file is in Diceware format (Roll, then Word). */
private class LineChecker(
    private val byWord: ExternalSorter,
    private val byRoll: ExternalSorter,
    private val output: WordListWriter,
) {
    private var diceware: Boolean? = null
    private var firstLine = 0L
    val isDiceware get() = diceware == true
    var rollLength = 0
        private set
    var count = 0L
        private set

    fun add(line: Long, text: String) {
        val fields = text.fields()
        val isDiceware = diceware ?: (fields.size >= 2 && fields[0].all { it.isDigit() }).also {
            diceware = it
            firstLine = line
        }
        val word = if (isDiceware) dicewareWord(line, fields) else plainWord(line, fields)
        byWord.add(Record(word.lowercase(), line, word))
        count++
    }

    private fun dicewareWord(line: Long, fields: List<String>): String {
        if (fields.size == 1) reject(ImportError.BadLine(line, LineProblem.MISSING_ROLL))
        if (fields.size > 2) reject(ImportError.BadLine(line, LineProblem.WORD_HAS_WHITESPACE))
        val roll = fields[0]
        if (!isRoll(roll)) reject(ImportError.BadLine(line, LineProblem.INVALID_ROLL))
        if (rollLength == 0) rollLength = roll.length
        if (roll.length != rollLength) reject(ImportError.BadLine(line, LineProblem.MIXED_ROLL_LENGTH))
        byRoll.add(Record(roll, line, fields[1]))
        return fields[1]
    }

    private fun plainWord(line: Long, fields: List<String>): String {
        // A Roll on this line but not on the first: the first line is the one missing its Roll.
        if (fields.size == 2 && fields[0].all { it.isDigit() }) {
            reject(ImportError.BadLine(firstLine, LineProblem.MISSING_ROLL))
        }
        if (fields.size > 1) reject(ImportError.BadLine(line, LineProblem.WORD_HAS_WHITESPACE))
        output.write(fields[0])
        return fields[0]
    }
}

/** Writes the Words of a Diceware-format file in Roll order, checking that every Roll appears exactly once. */
private suspend fun writeInRollOrder(
    byRoll: ExternalSorter,
    rollLength: Int,
    count: Long,
    output: WordListWriter,
    progress: (Double) -> Unit,
): Int {
    if (rollLength < MIN_DICE_PER_ROLL) reject(ImportError.WrongWordCount(count))
    // Records come in Roll order, so the first one that isn't the next Roll shows which Roll is missing.
    val expected = rollsInOrder(rollLength).map { it.joinToString("") }.iterator()
    var previous: Record? = null
    byRoll.forEachSorted(count, { progress(READ_SHARE + SORT_SHARE * it) }) { record ->
        previous?.let { if (it.key == record.key) reject(ImportError.RepeatedRoll(record.line, it.line)) }
        val roll = expected.next()
        if (record.key != roll) reject(ImportError.RollNotFound(roll))
        output.write(record.word)
        previous = record
    }
    if (expected.hasNext()) reject(ImportError.RollNotFound(expected.next()))
    return rollLength
}

private suspend fun checkNoDuplicateWords(byWord: ExternalSorter, count: Long, progress: (Double) -> Unit) {
    var previous: Record? = null
    byWord.forEachSorted(count, { progress(READ_SHARE + SORT_SHARE + SORT_SHARE * it) }) { record ->
        previous?.let { if (it.key == record.key) reject(ImportError.DuplicateWord(record.line, record.word, it.line)) }
        previous = record
    }
}

/** Reads one line without its `\n`, or null at the end; a line can't be longer than [MAX_LINE_BYTES]. */
private fun BufferedSource.readLineBounded(lineNumber: Long): String? {
    val newline = indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES + 1)
    return when {
        newline >= 0 -> readUtf8(newline).also { skip(1) }
        !request(MAX_LINE_BYTES + 1) -> if (exhausted()) null else readUtf8()
        else -> reject(ImportError.BadLine(lineNumber, LineProblem.LINE_TOO_LONG))
    }
}

/** The whitespace-separated fields of this line; any Unicode whitespace separates, not just ASCII. */
private fun String.fields(): List<String> {
    val fields = mutableListOf<String>()
    var start = -1
    for (i in indices) {
        if (this[i].isWhitespace()) {
            if (start >= 0) fields += substring(start, i)
            start = -1
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) fields += substring(start)
    return fields
}

private fun exactLog6(count: Long): Int? {
    var n = 0
    var power = 1L
    while (power < count) {
        power *= DIE_FACES
        n++
    }
    return if (power == count) n else null
}

internal class Record(val key: String, val line: Long, val word: String)

private val recordOrder = compareBy<Record>({ it.key }, { it.line })

/** Sorts more [Record]s than fit in memory: sorted runs of [RECORDS_PER_RUN] go to disk, then are merged. */
private class ExternalSorter(private val fileSystem: FileSystem, private val directory: Path) {
    private val chunk = ArrayList<Record>()
    private val runs = mutableListOf<Path>()
    private val open = mutableListOf<BufferedSource>()

    fun add(record: Record) {
        chunk += record
        if (chunk.size == RECORDS_PER_RUN) flush()
    }

    private fun flush() {
        if (chunk.isEmpty()) return
        chunk.sortWith(recordOrder)
        if (runs.isEmpty()) fileSystem.createDirectories(directory)
        val run = directory / "run${runs.size}"
        fileSystem.write(run) {
            for (r in chunk) {
                writeUtf8(r.key)
                    .writeByte('\t'.code)
                    .writeDecimalLong(r.line)
                    .writeByte('\t'.code)
                    .writeUtf8(r.word)
                    .writeByte('\n'.code)
            }
        }
        runs += run
        chunk.clear()
    }

    /** Calls [each] with every Record in order; [progress] gets 0–1 of [count]. */
    suspend fun forEachSorted(count: Long, progress: (Double) -> Unit, each: (Record) -> Unit) {
        flush()
        runs.mapTo(open) { fileSystem.source(it).buffer() }
        val heads = Array(open.size) { open[it].readRecord() }
        var done = 0L
        while (true) {
            // shortcut: a linear scan of the run heads, fine to ~6⁹ Words (~300 runs); use a heap for bigger lists.
            var min = -1
            var minHead: Record? = null
            for (i in heads.indices) {
                val head = heads[i] ?: continue
                if (minHead == null || recordOrder.compare(head, minHead) < 0) {
                    min = i
                    minHead = head
                }
            }
            if (minHead == null) break
            each(minHead)
            heads[min] = open[min].readRecord()
            if (++done % YIELD_EVERY == 0L) {
                yield()
                progress(done.toDouble() / count)
            }
        }
    }

    private fun BufferedSource.readRecord(): Record? {
        val text = readUtf8Line() ?: return null
        val first = text.indexOf('\t')
        val second = text.indexOf('\t', first + 1)
        return Record(text.substring(0, first), text.substring(first + 1, second).toLong(), text.substring(second + 1))
    }

    fun close() = open.forEach { it.close() }
}

/** Writes Words in Roll order plus the offset of each (and of the end), hashing the Words as it goes. */
private class WordListWriter(fileSystem: FileSystem, directory: Path) {
    private val hashing = HashingSink.sha256(fileSystem.sink(directory / WORDS_FILE))
    private val words: BufferedSink = hashing.buffer()
    private val offsets: BufferedSink = fileSystem.sink(directory / OFFSETS_FILE).buffer().writeLong(0)
    private var offset = 0L

    fun write(word: String) {
        words.writeUtf8(word).writeByte('\n'.code)
        offset += word.utf8Size() + 1
        offsets.writeLong(offset)
    }

    /** Flushes both files and returns the SHA-256 of the Words, one per line in Roll order. */
    fun finish(): String {
        words.flush()
        offsets.flush()
        return hashing.hash.hex()
    }

    fun close() {
        words.close()
        offsets.close()
    }
}

/** A Word List on disk, as [stageWordList] wrote it: a Roll is resolved by reading just its Word. */
internal class DiskWordList(
    override val dicePerRoll: Int,
    fileSystem: FileSystem,
    directory: Path,
    name: String,
) : WordList {
    private val words = fileSystem.openReadOnly(directory / wordListFileName(name, WORDS_FILE))
    private val offsets = fileSystem.openReadOnly(directory / wordListFileName(name, OFFSETS_FILE))

    // shortcut: reads a few bytes on the caller's (main) thread per Roll; move rolling off-main if that ever
    // shows in ANR or StrictMode reports.
    override fun resolve(roll: List<Int>): String {
        requireRoll(roll, dicePerRoll)
        // The Roll's place in Roll order: its dice read as a base-6 number, 11…1 first.
        val place = roll.fold(0L) { acc, die -> acc * DIE_FACES + (die - 1) }
        val bounds = okio.Buffer()
        offsets.read(place * Long.SIZE_BYTES, bounds, 2L * Long.SIZE_BYTES)
        val start = bounds.readLong()
        val end = bounds.readLong()
        val word = okio.Buffer()
        words.read(start, word, end - start - 1)
        return word.readUtf8()
    }

    fun close() {
        words.close()
        offsets.close()
    }
}

/** Reads [byteCount] bytes at [offset] fully: a FileHandle read may return fewer. */
private fun okio.FileHandle.read(offset: Long, sink: okio.Buffer, byteCount: Long) {
    var read = 0L
    while (read < byteCount) {
        val n = read(offset + read, sink, byteCount - read)
        check(n > 0) { "Word List file is truncated" }
        read += n
    }
}
