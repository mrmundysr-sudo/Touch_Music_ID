package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import com.mundymorning.touchmusicid.net.HttpFetcher
import com.mundymorning.touchmusicid.net.HttpResponse
import com.mundymorning.touchmusicid.net.UrlConnectionFetcher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/**
 * DIAGNOSTIC ONLY (no production code involved beyond the real lookup classes). Opt-in: LIVE_DIAG=1.
 * Traces representative artists through the REAL Wikidata/Wikipedia lookup used by the app, recording every HTTP outcome
 * separately: HTTP status, API-level `error` objects, non-JSON bodies, IO failures, and genuine empty results.
 * Nothing sensitive is involved (public API, no credentials); non-JSON bodies are logged as at most 60 printable characters.
 * Output prefixes: DIAG (detail), DIAGSUM (summary shown in the CI step summary).
 */
class LookupDiagnosticsLiveTest {
    private val enabled get() = System.getenv("LIVE_DIAG") == "1"

    private class Recording(private val inner: HttpFetcher) : HttpFetcher {
        class Ev(val action: String, val code: Int?, val ms: Long, val kind: String, val detail: String)
        val events = mutableListOf<Ev>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            val action = Regex("action=(\\w+)").find(url)?.groupValues?.get(1) ?: if ("rest_v1" in url) "rest_summary" else "other"
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1_000_000
            try {
                val r = inner.get(url, headers)
                val (kind, detail) = when {
                    r.code == 429 -> "HTTP_429" to "retry-after=${r.retryAfterSeconds}"
                    r.code !in 200..299 -> (if (r.code == 404) "HTTP_404" else "HTTP_ERROR") to "code=${r.code}"
                    else -> try {
                        val o = JSONObject(r.body)
                        if (o.has("error")) "API_ERROR" to "code=${o.optJSONObject("error")?.optString("code")} info=${o.optJSONObject("error")?.optString("info")?.take(80)}"
                        else "OK" to "bytes=${r.body.length}"
                    } catch (e: org.json.JSONException) {
                        "PARSE_ERROR" to "non-JSON body: " + r.body.take(60).filter { it.code in 32..126 }
                    }
                }
                events += Ev(action, r.code, ms(), kind, detail)
                return r
            } catch (e: IOException) {
                events += Ev(action, null, ms(), "IO_ERROR", e.javaClass.simpleName + ": " + (e.message ?: "").take(80).filter { it.code in 32..126 })
                throw e
            }
        }
        fun drain(): List<Ev> = events.toList().also { events.clear() }
    }

    private val artists = listOf(
        "Morgan Wallen", "Queen", "Beyoncé", "AC/DC", "P!nk", "Jay-Z", "Guns N' Roses", "Sinéad O'Connor", "Panic! at the Disco",
        "Florence + the Machine", "deadmau5", "Taylor Swift", "Luke Combs", "Post Malone", "Kendrick Lamar", "The Weeknd", "Drake",
        "Prince", "Pink Floyd", "Kesha", "will.i.am", "Mötley Crüe", "Bad Bunny", "BTS", "Zach Bryan", "Chris Stapleton",
    )

    private fun fmt(evs: List<Recording.Ev>) = evs.joinToString("; ") { "${it.action}:${it.kind}(${it.code ?: "-"},${it.ms}ms,${it.detail})" }

    private suspend fun <T> attempt(block: suspend () -> T): Pair<T?, String?> =
        try { block() to null } catch (e: RateLimitedException) { null to "RateLimited" } catch (e: ServerException) { null to "Server(${e.code})" }
        catch (e: IOException) { null to "IO(${e.javaClass.simpleName})" }

    private fun statusOf(l: Lookup?, err: String?) = err?.let { "LOOKUP_FAILED[$it]" } ?: when (l!!.status) {
        MatchStatus.VERIFIED -> "VERIFIED(${l.entity?.qid})"; MatchStatus.AMBIGUOUS -> "AMBIGUOUS(${l.candidates.size})"
        MatchStatus.NOT_FOUND -> "NOT_FOUND"; MatchStatus.LOOKUP_FAILED -> "LOOKUP_FAILED"
    }

    @Test fun traceRepresentativeArtistsThroughTheLiveLookup() = runBlocking {
        assumeTrue("LIVE_DIAG not set", enabled)
        println("DIAG env java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} fetcher=UrlConnectionFetcher(timeouts 8s/12s, 150ms spacing, gzip)")
        println("DIAG code-path: WikiApi -> KnowledgeResolver (same classes the app wires in AppContainer; app adds KnowledgeRepository + Room cache)")
        // Requirement: confirm the ACTUAL label fields Wikidata returns for the artists that used to fail every time.
        run {
            val rec = Recording(UrlConnectionFetcher())
            for ((name, qid) in listOf("Morgan Wallen" to "Q29561472", "Beyoncé" to "Q36153", "Post Malone" to "Q21621919", "Kendrick Lamar" to "Q130798", "The Weeknd" to "Q2121062", "Bad Bunny" to "Q44333953")) {
                for (langs in listOf("en", "en%7Cmul")) {
                    val (r, err) = attempt { rec.get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&props=labels%7Caliases&languages=$langs&format=json") }
                    val ent = r?.let { runCatching { JSONObject(it.body).getJSONObject("entities").getJSONObject(qid) }.getOrNull() }
                    fun keys(o: JSONObject?) = o?.keys()?.asSequence()?.toList() ?: emptyList()
                    val labels = ent?.optJSONObject("labels"); val aliases = ent?.optJSONObject("aliases")
                    println("DIAGLABEL $name $qid request languages=${langs.replace("%7C", "|")}: label languages=${keys(labels)} " +
                        keys(labels).joinToString { "$it='${labels!!.getJSONObject(it).optString("value")}'" } +
                        " | alias languages=${keys(aliases)} | ${err ?: "ok"}")
                    rec.drain()
                }
            }
        }
        // Pace requests: an unpaced run (the previous attempt) trips Wikidata's per-IP rate limit (HTTP 429, Retry-After 6-8 s), which
        // measures the diagnostic's own traffic, not the app's (about five requests per song).
        val paceMs = (System.getenv("LIVE_DIAG_PACE_MS") ?: "2500").toLong(); val passGapMs = (System.getenv("LIVE_DIAG_PASS_GAP_MS") ?: "30000").toLong()
        var apiErrorEvents = 0; var rateLimited = 0; var absorbed = 0; var finalFailures = 0
        val results = LinkedHashMap<String, MutableList<String>>()
        for (pass in 1..3) {
            if (pass > 1) Thread.sleep(passGapMs)
            for (name in artists) {
                Thread.sleep(paceMs)
                val rec = Recording(UrlConnectionFetcher())
                val api = WikiApi(rec); val resolver = KnowledgeResolver(api)
                if (pass == 1) {
                    println("DIAG ===== $name (pass 1, full trace)")
                    val (hits7, e7) = attempt { api.searchEntities(name, 7) }
                    println("DIAG  search(limit 7): ${hits7?.size ?: "FAILED $e7"} hits | ${fmt(rec.drain())}")
                    val ids7 = hits7.orEmpty().map { it.id }
                    val (ents, ee) = attempt { api.entities(ids7) }
                    println("DIAG  entities(${ids7.size}): ${ents?.size ?: "FAILED $ee"} parsed | ${fmt(rec.drain())}")
                    ents?.let { m ->
                        for (id in ids7) {
                            val e = m[id] ?: run { println("DIAG   $id MISSING from entities response"); null } ?: continue
                            println("DIAG   $id label=${e.label} nameMatch=${resolver.nameMatches(e, name)} musical=${resolver.isMusicalEntity(e)} " +
                                "P31=${e.refs("P31")} P106=${e.refs("P106")} ids[MB=${e.has("P434")},Spotify=${e.has("P1902")},AllMusic=${e.has("P1728")}] enwiki=${e.enwikiTitle}")
                        }
                    }
                    val (hits20, e20) = attempt { api.searchEntities(name, 20) }
                    val ents20 = hits20?.let { h -> attempt { api.entities(h.map { it.id }) }.first }
                    val q20 = hits20.orEmpty().filter { h -> ents20?.get(h.id)?.let { resolver.nameMatches(it, name) && resolver.isMusicalEntity(it) } == true }.map { h -> h.id to hits20!!.indexOf(h) + 1 }
                    val q7 = ids7.filter { id -> ents?.get(id)?.let { resolver.nameMatches(it, name) && resolver.isMusicalEntity(it) } == true }
                    println("DIAG  qualified in top7=$q7 ; qualified in top20 (id to rank)=$q20 ${e20?.let { "FAILED $it" } ?: ""} | ${fmt(rec.drain())}")
                    val (raw, er) = attempt { rec.get("https://en.wikipedia.org/api/rest_v1/page/summary/${name.replace(' ', '_').let { java.net.URLEncoder.encode(it, "UTF-8") }}?redirect=true") }
                    val o = raw?.takeIf { it.code in 200..299 }?.let { runCatching { JSONObject(it.body) }.getOrNull() }
                    println("DIAG  wikipedia exact-title summary: ${raw?.code ?: "FAILED $er"} type=${o?.optString("type")} wikibase_item=${o?.optString("wikibase_item")} description=${o?.optString("description")?.take(60)} | ${fmt(rec.drain())}")
                }
                val (lookup, err) = attempt { resolver.resolveArtist(name) }
                val evs = rec.drain()
                val bad = evs.filter { it.kind != "OK" }
                apiErrorEvents += bad.count { it.kind == "API_ERROR" }
                rateLimited += bad.count { it.kind == "HTTP_429" }
                val s = statusOf(lookup, err)
                if (bad.isNotEmpty() && s.startsWith("VERIFIED")) absorbed++
                if (err != null) finalFailures++
                results.getOrPut(name) { mutableListOf() } += s
                println("DIAG  PRODUCTION resolveArtist pass $pass: $s${if (bad.isNotEmpty()) "  NON-OK EVENTS: ${fmt(bad)}" else ""}")
            }
        }
        // Repository + cache behaviour for Morgan Wallen with real network (cache questions 2 and 3 are covered deterministically elsewhere).
        val cache = InMemoryKnowledgeCache(); var now = 1_000_000_000L
        val repo = KnowledgeRepository(KnowledgeResolver(WikiApi(UrlConnectionFetcher())), cache, clock = { now })
        val song = Song("Diagnostic Title", "Morgan Wallen")
        val first = repo.load(song); now += 1000; val second = repo.load(song)
        fun d(r: KnowledgeResult) = when (r) { is KnowledgeResult.Found -> "Found(${r.freshness}, artist=${r.bundle.artist.status})"; is KnowledgeResult.Failed -> "Failed(${r.kind})" }
        println("DIAG repository Morgan Wallen: first=${d(first)} second=${d(second)} cachedEntries=${cache.map.keys.filter { it.startsWith("k1|") }.size}")

        println("DIAGSUM | artist | pass1 | pass2 | pass3 | stable |")
        for ((n, l) in results) println("DIAGSUM | $n | ${l.joinToString(" | ")} | ${if (l.toSet().size == 1) "yes" else "NO (flaky)"} |")
        val flaky = results.filter { it.value.toSet().size > 1 }.keys
        val notVerified = results.filter { !it.value.all { s -> s.startsWith("VERIFIED") } }.keys
        val required = listOf("Morgan Wallen", "Beyoncé", "Post Malone", "Kendrick Lamar", "The Weeknd", "Bad Bunny")
        println("DIAGSUM required six VERIFIED in every pass: ${required.associateWith { n -> results[n]!!.all { it.startsWith("VERIFIED") } }}")
        println("DIAGSUM production lookups: ${results.size * 3}; Wikidata 'too busy' error bodies: $apiErrorEvents; HTTP 429 responses: $rateLimited; lookups with a transient error that the retry absorbed (ended VERIFIED): $absorbed; ended LOOKUP_FAILED: $finalFailures; ended NOT_FOUND: ${results.values.sumOf { l -> l.count { it == "NOT_FOUND" } }}")
        println("DIAGSUM flaky artists: ${flaky.ifEmpty { listOf("none") }}")
        println("DIAGSUM not VERIFIED in every pass: ${notVerified.ifEmpty { listOf("none") }}")
    }
}
