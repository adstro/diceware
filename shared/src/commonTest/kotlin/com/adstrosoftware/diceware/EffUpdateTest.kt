package com.adstrosoftware.diceware

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class EffUpdateTest {
    private val fileSystem = FakeFileSystem()

    /** What eff.org serves: the bundled lists, unless a test changes one. */
    private val served = EffWordList.entries.associateWith { bundledText(it) }.toMutableMap()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        val list = EffWordList.entries.first { request.url.toString() == it.url }
        respond(served.getValue(list), HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
    }
    private val requested = mutableListOf<String>()

    @Test
    fun theSameListsOnEffOrgAreUpToDate() = runTest {
        val library = library()

        library.checkForEffUpdates()

        assertEquals(EffUpdateState.UpToDate, library.effUpdateState.value)
        assertEquals(
            listOf(
                "https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt",
                "https://www.eff.org/files/2016/09/08/eff_short_wordlist_1.txt",
                "https://www.eff.org/files/2016/09/08/eff_short_wordlist_2_0.txt",
            ),
            requested,
        )
    }

    @Test
    fun aChangedListShowsWhatChangedAndIsUsedOnlyOnceTheUserAppliesIt() = runTest {
        // eff.org swaps two Words of Short #1 and moves Roll 1111's Word to 6666.
        served[EffWordList.SHORT_1] = bundledText(EffWordList.SHORT_1)
            .replace("\tshort_11112\n", "\tkiwi\n").replace("\tshort_11113\n", "\tlime\n")
            .replace("1111\tshort_11111\n", "1111\tshort_16666\n").replace("6666\tshort_16666\n", "6666\tshort_11111\n")
        val library = library()

        library.checkForEffUpdates()

        assertEquals(
            EffUpdateState.UpdateAvailable(
                listOf(
                    EffWordListChange(
                        EffWordList.SHORT_1,
                        2,
                        2,
                        listOf("kiwi", "lime"),
                        listOf("short_11112", "short_11113"),
                    ),
                ),
            ),
            library.effUpdateState.value,
        )
        val shortOne = library.wordList(EffWordList.SHORT_1)
        assertEquals("short_11112", shortOne.resolve(listOf(1, 1, 1, 2)))

        library.applyEffUpdate()

        library.effUpdateState.first { it == EffUpdateState.Updated }
        val updated = library.wordList(EffWordList.SHORT_1)
        assertEquals("kiwi", updated.resolve(listOf(1, 1, 1, 2)))
        assertEquals("short_16666", updated.resolve(listOf(1, 1, 1, 1)))
        assertEquals(listOf(false, true, false), library.loaded().map { it.updated })
        // Still updated in the next session; then a check finds it up to date.
        val next = library()
        assertEquals(listOf(false, true, false), next.loaded().map { it.updated })
        assertEquals("kiwi", next.wordList(EffWordList.SHORT_1).resolve(listOf(1, 1, 1, 2)))
        next.checkForEffUpdates()
        assertEquals(EffUpdateState.UpToDate, next.effUpdateState.value)
    }

    @Test
    fun dismissingAnUpdateChangesNothing() = runTest {
        served[EffWordList.LARGE] = bundledText(EffWordList.LARGE).replace("\tlarge1111\n", "\tkiwi\n")
        val library = library()
        library.checkForEffUpdates()

        library.dismissEffUpdate()
        library.applyEffUpdate()
        testScheduler.advanceUntilIdle()

        assertEquals(EffUpdateState.Idle, library.effUpdateState.value)
        assertEquals("large1111", library.wordList(EffWordList.LARGE).resolve(listOf(1, 1, 1, 1)))
        assertEquals(listOf(false, false, false), library().loaded().map { it.updated })
    }

    @Test
    fun restoringBundledListsRevertsAnUpdate() = runTest {
        served[EffWordList.LARGE] = bundledText(EffWordList.LARGE).replace("\tlarge1111\n", "\tkiwi\n")
        val library = library()
        library.checkForEffUpdates()
        library.applyEffUpdate()
        library.effUpdateState.first { it == EffUpdateState.Updated }

        library.restoreBundledEffWordLists()

        library.wordLists.first { lists -> lists.none { it.updated } }
        assertEquals("large1111", library.wordList(EffWordList.LARGE).resolve(listOf(1, 1, 1, 1)))
        assertEquals(listOf("/lists/index.json".toPath()), fileSystem.list("/lists".toPath()))
        val next = library()
        assertEquals(listOf(false, false, false), next.loaded().map { it.updated })
        assertEquals("large1111", next.wordList(EffWordList.LARGE).resolve(listOf(1, 1, 1, 1)))
    }

    @Test
    fun aMalformedListFailsTheCheckNamingTheLineAndNothingChanges() = runTest {
        served[EffWordList.LARGE] = bundledText(EffWordList.LARGE).replace("\tlarge1111\n", "\tkiwi\n")
        served[EffWordList.SHORT_2] = bundledText(EffWordList.SHORT_2).replace("\tshort_21112\n", "\tshort_21111\n")

        assertFailsAndChangesNothing(
            EffUpdateError.Invalid(EffWordList.SHORT_2, ImportError.DuplicateWord(2, "short_21111", 1)),
        )
    }

    @Test
    fun aFileOver1MbIsRefusedWithOrWithoutAContentLength() = runTest {
        handler = { respond(ByteArray(MAX_DOWNLOAD_BYTES.toInt() + 1) { 'a'.code.toByte() }, HttpStatusCode.OK) }
        assertFailsAndChangesNothing(EffUpdateError.TooLarge)

        handler = {
            respond(ByteReadChannel(ByteArray(MAX_DOWNLOAD_BYTES.toInt() + 1) { 'a'.code.toByte() }), HttpStatusCode.OK)
        }
        assertFailsAndChangesNothing(EffUpdateError.TooLarge)
    }

    @Test
    fun aRedirectToAnotherHostToHttpOrToAMalformedUrlIsRefused() = runTest {
        val locations = listOf(
            "https://eff.example/eff_large_wordlist.txt",
            "http://www.eff.org/files/eff_large_wordlist.txt",
            "https://www.eff.org:notaport/files/eff_large_wordlist.txt",
        )
        for (location in locations) {
            // Wherever it leads serves a valid list, so only the redirect itself can fail the check.
            handler = { request ->
                if (request.url.toString() == location) {
                    respond(bundledText(EffWordList.LARGE), HttpStatusCode.OK)
                } else {
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, location))
                }
            }
            assertFailsAndChangesNothing(EffUpdateError.Redirected)
        }
    }

    @Test
    fun aRedirectWithinWwwEffOrgIsFollowed() = runTest {
        handler = { request ->
            val path = request.url.encodedPath
            if (path.startsWith("/moved/")) {
                respond(served.getValue(EffWordList.entries.first { path.endsWith(it.fileName) }), HttpStatusCode.OK)
            } else {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, path.replace("/files/", "/moved/")))
            }
        }
        val library = library()

        library.checkForEffUpdates()

        assertEquals(EffUpdateState.UpToDate, library.effUpdateState.value)
    }

    @Test
    fun offlineOrAServerErrorFailsTheCheck() = runTest {
        handler = { throw IOException("Unable to resolve host www.eff.org") }
        assertFailsAndChangesNothing(EffUpdateError.Offline)

        handler = { respond("", HttpStatusCode.NotFound) }
        assertFailsAndChangesNothing(EffUpdateError.HttpStatus(404))
    }

    private suspend fun TestScope.assertFailsAndChangesNothing(expected: EffUpdateError) {
        val library = library()

        library.checkForEffUpdates()
        library.applyEffUpdate()
        testScheduler.advanceUntilIdle()

        assertEquals(EffUpdateState.Failed(expected), library.effUpdateState.value)
        assertEquals("large1111", library.wordList(EffWordList.LARGE).resolve(listOf(1, 1, 1, 1)))
        assertEquals(emptyList(), fileSystem.list("/lists".toPath()))
    }

    private fun TestScope.library() = WordListLibrary(
        { EffWordList.entries.associateWith { WordList.parse(bundledText(it)) } },
        fileSystem,
        "/lists".toPath(),
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        httpEngine = {
            MockEngine { request ->
                requested += request.url.toString()
                handler(request)
            }
        },
    )

    private suspend fun WordListLibrary.loaded() = wordLists.first { it.isNotEmpty() }

    /** Each test EFF list differs by its prefix: `large1111`, `short_11111`, … in Diceware format. */
    private fun bundledText(list: EffWordList) =
        rolls(4).joinToString("\n", postfix = "\n") { "$it\t${list.name.lowercase()}$it" }

    private fun rolls(dice: Int): List<String> =
        if (dice == 0) listOf("") else rolls(dice - 1).flatMap { prefix -> (1..6).map { prefix + it } }
}
