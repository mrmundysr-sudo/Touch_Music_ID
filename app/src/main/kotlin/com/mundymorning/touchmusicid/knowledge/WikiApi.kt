package com.mundymorning.touchmusicid.knowledge

import com.mundymorning.touchmusicid.net.HttpFetcher
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException

class RateLimitedException(val retryAfterSeconds: Long?) : IOException("Rate limited")
class ServerException(val code: Int, val retryAfterSeconds: Long? = null) : IOException("HTTP $code")

/** MediaWiki/Wikibase answered HTTP 200 with an `error` object (e.g. cirrussearch-too-busy-error, maxlag). It is a FAILURE, not "no results". */
class ApiErrorException(val code: String, val info: String?, val retryAfterSeconds: Long? = null) : IOException("API error $code")

/** The body was not JSON, or a field every valid response has was missing. Never interpreted as "no results". */
class MalformedResponseException(detail: String) : IOException("Malformed response: $detail")

/** Raised by the resolver when every part of a lookup failed; carries the user-facing failure kind. */
class LookupFailedException(val kind: KnowledgeResult.Kind) : IOException("Lookup failed: $kind")

/** Maps a lookup exception to the failure kind shown to the user; null for anything that is not an I/O style failure. */
fun Throwable.lookupFailureKind(): KnowledgeResult.Kind? = when (this) {
    is LookupFailedException -> kind
    is RateLimitedException -> KnowledgeResult.Kind.RATE_LIMITED
    is ServerException, is ApiErrorException, is MalformedResponseException -> KnowledgeResult.Kind.SERVER
    is IOException -> KnowledgeResult.Kind.OFFLINE
    else -> null
}

/**
 * Thin, polite client over the official Wikipedia and Wikidata endpoints. Throws IOException subclasses on failure.
 * Every response is validated: HTTP status, JSON syntax, an `error` object under HTTP 200, and the required top-level field.
 * An empty but well-formed result (for example `"search": []`) is the ONLY thing reported as "nothing found".
 * Transient failures are retried at most [retries] times with short bounded backoff, honouring Retry-After (capped).
 */
class WikiApi(
    private val http: HttpFetcher,
    private val wikipediaHost: String = "en.wikipedia.org",
    private val wikidataHost: String = "www.wikidata.org",
    private val retries: Int = 2,
    private val backoffMillis: List<Long> = listOf(400, 1000),
    /** A Retry-After longer than this is not waited out; the failure is reported instead. */
    private val maxRetryAfterMillis: Long = 5000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun summary(title: String): ArticleSummary? {
        val path = enc(title.replace(' ', '_')).replace("+", "%20")
        val url = "https://$wikipediaHost/api/rest_v1/page/summary/$path?redirect=true"
        return withRetry {
            val r = http.get(url)
            if (r.code == 404) return@withRetry null
            WikiParsers.parseSummary(validated(checked(r), "title"))
        }
    }

    suspend fun searchArticles(query: String, limit: Int = 5): List<SearchHit> {
        val url = "https://$wikipediaHost/w/api.php?action=query&list=search&srsearch=${enc(query)}" +
            "&srlimit=$limit&srnamespace=0&srprop=snippet&format=json&formatversion=2&utf8=1"
        return WikiParsers.parseSearch(getBody(url, "query"))
    }

    suspend fun searchEntities(name: String, limit: Int = 7): List<EntityHit> {
        val url = "https://$wikidataHost/w/api.php?action=wbsearchentities&search=${enc(name)}" +
            "&language=en&uselang=en&type=item&limit=$limit&format=json"
        return WikiParsers.parseEntitySearch(getBody(url, "search"))
    }

    /**
     * Languages are `en` AND `mul`. Wikidata stores names that are the same in every language (most stage names, many places)
     * only as a language-neutral `mul` label, which an `en`-only request silently omits.
     */
    suspend fun entities(ids: Collection<String>, full: Boolean = true): Map<String, WikidataEntity> {
        if (ids.isEmpty()) return emptyMap()
        val props = if (full) "labels|descriptions|aliases|claims|sitelinks" else "labels"
        val url = "https://$wikidataHost/w/api.php?action=wbgetentities&ids=${ids.distinct().take(50).joinToString("%7C")}" +
            "&props=${enc(props)}&languages=en%7Cmul&sitefilter=enwiki&format=json"
        return WikiParsers.parseEntities(getBody(url, "entities"))
    }

    // ---- validation + retry -------------------------------------------------------------------------------

    private suspend fun getBody(url: String, requiredField: String): String =
        withRetry { validated(checked(http.get(url)), requiredField) }

    private fun checked(r: HttpResponse): HttpResponse {
        if (r.code == 429) throw RateLimitedException(r.retryAfterSeconds)
        if (r.code !in 200..299) throw ServerException(r.code, r.retryAfterSeconds)
        return r
    }

    private fun validated(r: HttpResponse, requiredField: String): String {
        val o = try { JSONObject(r.body) } catch (e: JSONException) { throw MalformedResponseException("body is not JSON") }
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw ApiErrorException(err?.optString("code").orEmpty().ifBlank { "unknown" }, err?.optString("info")?.take(120), r.retryAfterSeconds)
        }
        if (!o.has(requiredField)) throw MalformedResponseException("missing \"$requiredField\"")
        return r.body
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (attempt >= retries || !isTransient(e)) throw e
                val wait = waitFor(e, attempt) ?: throw e
                pause(wait)
                attempt++
            }
        }
    }

    private fun isTransient(e: IOException): Boolean = when (e) {
        is RateLimitedException, is MalformedResponseException -> true
        is ServerException -> e.code in 500..599
        is ApiErrorException -> e.code in TRANSIENT_API_CODES || e.code.startsWith("internal_api_error")
        is SocketTimeoutException, is UnknownHostException -> false // already slow / offline: retrying only delays the message
        is LookupFailedException -> false
        else -> true
    }

    /** Milliseconds to wait before the next attempt, or null when a server-requested wait is too long to be worth it. */
    private fun waitFor(e: IOException, attempt: Int): Long? {
        val retryAfter = when (e) {
            is RateLimitedException -> e.retryAfterSeconds
            is ServerException -> e.retryAfterSeconds
            is ApiErrorException -> e.retryAfterSeconds
            else -> null
        }
        if (retryAfter != null) {
            val ms = retryAfter.coerceAtLeast(0) * 1000
            return if (ms > maxRetryAfterMillis) null else ms
        }
        return backoffMillis.getOrElse(attempt) { backoffMillis.last() }.coerceAtMost(maxRetryAfterMillis)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** MediaWiki error codes that mean "try again shortly". */
        val TRANSIENT_API_CODES = setOf("cirrussearch-too-busy-error", "maxlag", "ratelimited", "readonly", "cirrussearch-backend-error")
    }
}
