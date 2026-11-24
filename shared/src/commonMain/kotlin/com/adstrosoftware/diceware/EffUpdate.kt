package com.adstrosoftware.diceware

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlin.coroutines.cancellation.CancellationException

/** Word Lists screen state for a check of eff.org for updated EFF Word Lists. */
sealed interface EffUpdateState {
    data object Idle : EffUpdateState
    data object Checking : EffUpdateState

    /** eff.org serves the same Word Lists the app uses. */
    data object UpToDate : EffUpdateState

    /** eff.org serves different Word Lists; nothing changes unless the user applies them. */
    data class UpdateAvailable(val changes: List<EffWordListChange>) : EffUpdateState
    data object Updated : EffUpdateState

    /** Nothing was changed. */
    data class Failed(val error: EffUpdateError) : EffUpdateState
}

/** What an update would change in one EFF Word List: how many Words come and go, with a few of each. */
data class EffWordListChange(
    val wordList: EffWordList,
    val wordsAdded: Long,
    val wordsRemoved: Long,
    val sampleAdded: List<String>,
    val sampleRemoved: List<String>,
)

sealed interface EffUpdateError {
    /** eff.org could not be reached: no connection, DNS, TLS or a timeout. */
    data object Offline : EffUpdateError
    data class HttpStatus(val code: Int) : EffUpdateError

    /** Bigger than [MAX_DOWNLOAD_BYTES]. */
    data object TooLarge : EffUpdateError

    /** Redirected off www.eff.org or off HTTPS. */
    data object Redirected : EffUpdateError

    /** The downloaded file fails the same validation as an import. */
    data class Invalid(val wordList: EffWordList, val error: ImportError) : EffUpdateError
    data object StorageFailed : EffUpdateError
}

internal class EffUpdateFailed(val error: EffUpdateError) : Exception(error.toString())

private fun fail(error: EffUpdateError): Nothing = throw EffUpdateFailed(error)

internal const val MAX_DOWNLOAD_BYTES = 1L shl 20
private const val MAX_REDIRECTS = 5
private const val SAMPLE_SIZE = 5

/** The platform's HTTP engine: OkHttp on Android, Darwin (NSURLSession) on iOS. */
internal expect fun platformHttpEngine(): HttpClientEngine

/** Redirects are followed by [download] itself, so it can refuse those to another host. */
internal fun httpClient(engine: HttpClientEngine) = HttpClient(engine) { followRedirects = false }

/** GETs [url], following only HTTPS redirects on the same host, into memory: at most [MAX_DOWNLOAD_BYTES]. */
internal suspend fun HttpClient.download(url: String): Buffer {
    var current = Url(url)
    repeat(MAX_REDIRECTS + 1) {
        // Each engine throws its own types for network failures, and every one of them means Offline.
        @Suppress("TooGenericExceptionCaught")
        val body = try {
            prepareGet(current).execute { response ->
                when {
                    response.status.value in 300..399 -> {
                        val location = response.headers[HttpHeaders.Location] ?: fail(EffUpdateError.Redirected)
                        val next = runCatching { URLBuilder(current).takeFrom(location).build() }.getOrNull() ?: fail(EffUpdateError.Redirected)
                        if (next.protocol != URLProtocol.HTTPS || next.host != current.host) fail(EffUpdateError.Redirected)
                        current = next
                        null
                    }
                    response.status != HttpStatusCode.OK -> fail(EffUpdateError.HttpStatus(response.status.value))
                    (response.contentLength() ?: 0) > MAX_DOWNLOAD_BYTES -> fail(EffUpdateError.TooLarge)
                    else -> {
                        // Read one byte past the cap to tell "exactly the cap" from "too large" without a Content-Length.
                        val bytes = response.bodyAsChannel().readRemaining(MAX_DOWNLOAD_BYTES + 1).readByteArray()
                        if (bytes.size > MAX_DOWNLOAD_BYTES) fail(EffUpdateError.TooLarge)
                        Buffer().write(bytes)
                    }
                }
            }
        } catch (e: EffUpdateFailed) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(EffUpdateError.Offline)
        }
        if (body != null) return body
    }
    fail(EffUpdateError.Redirected)
}

/** Every Word in Roll order, by resolving each Roll in turn (ADR 0002: there is no index access). */
internal fun WordList.wordsInRollOrder(): Sequence<String> = rollsInOrder(dicePerRoll).map(::resolve)

/** The SHA-256 an import would give these Words: each followed by `\n`, in Roll order. */
internal fun WordList.sha256(): String {
    val hashing = HashingSink.sha256(blackholeSink())
    hashing.buffer().use { sink -> wordsInRollOrder().forEach { sink.writeUtf8(it).writeByte('\n'.code) } }
    return hashing.hash.hex()
}

internal fun change(list: EffWordList, current: WordList, downloaded: WordList): EffWordListChange {
    val old = current.wordsInRollOrder().toSet()
    val new = downloaded.wordsInRollOrder().toSet()
    val added = new - old
    val removed = old - new
    return EffWordListChange(list, added.size.toLong(), removed.size.toLong(), added.take(SAMPLE_SIZE), removed.take(SAMPLE_SIZE))
}
