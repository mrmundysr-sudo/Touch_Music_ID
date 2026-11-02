package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.knowledge.ClaimValue
import com.mundymorning.touchmusicid.net.HttpFetcher
import com.mundymorning.touchmusicid.net.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Scripted HTTP: the handler returns a response, or null to simulate "network unreachable". */
class FakeHttp(var handler: (String) -> HttpResponse?) : HttpFetcher {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        urls += url
        return handler(url) ?: throw IOException("offline")
    }
}

fun ok(body: String) = HttpResponse(200, body)

/** Builds a Wikidata entity JSON object. Claim values: "Q.."=item ref, ClaimValue.Time=time, other String=text. */
fun entityJson(
    id: String, label: String?, description: String? = null, aliases: List<String> = emptyList(),
    claims: Map<String, List<Any>> = emptyMap(), enwiki: String? = null,
): JSONObject = JSONObject().apply {
    put("id", id)
    label?.let { put("labels", JSONObject().put("en", JSONObject().put("language", "en").put("value", it))) }
    description?.let { put("descriptions", JSONObject().put("en", JSONObject().put("value", it))) }
    if (aliases.isNotEmpty()) put("aliases", JSONObject().put("en", JSONArray(aliases.map { JSONObject().put("value", it) })))
    enwiki?.let { put("sitelinks", JSONObject().put("enwiki", JSONObject().put("title", it))) }
    put("claims", JSONObject().apply {
        for ((prop, values) in claims) {
            put(prop, JSONArray(values.map { v ->
                val dv = when {
                    v is ClaimValue.Time -> JSONObject().put("type", "time").put("value", JSONObject().put("time", v.iso).put("precision", v.precision))
                    v is String && Regex("Q\\d+").matches(v) -> JSONObject().put("type", "wikibase-entityid").put("value", JSONObject().put("entity-type", "item").put("id", v))
                    else -> JSONObject().put("type", "string").put("value", v.toString())
                }
                JSONObject().put("rank", "normal").put("mainsnak", JSONObject().put("datavalue", dv))
            }))
        }
    })
}

fun entitiesJson(vararg e: JSONObject): String =
    JSONObject().put("entities", JSONObject().apply { e.forEach { put(it.getString("id"), it) } }).toString()

fun searchJson(vararg hits: Triple<String, String, String?>): String =
    JSONObject().put("search", JSONArray(hits.map { (id, l, d) -> JSONObject().put("id", id).put("label", l).put("description", d) })).toString()

fun summaryJson(title: String, extract: String, description: String? = null, type: String = "standard"): String =
    JSONObject().put("type", type).put("title", title).put("extract", extract).put("description", description)
        .put("content_urls", JSONObject().put("desktop", JSONObject().put("page", "https://en.wikipedia.org/wiki/${title.replace(' ', '_')}"))).toString()

fun wpSearchJson(vararg titles: String): String =
    JSONObject().put("query", JSONObject().put("search", JSONArray(titles.map { JSONObject().put("title", it).put("snippet", "<span>x</span>") }))).toString()

/** A tiny fake of Wikidata + Wikipedia driven by a database of entities and per-query search results. */
class FakeWiki(
    val db: Map<String, JSONObject>,
    val entitySearch: Map<String, String>,        // search term -> response json
    val summaries: Map<String, String> = emptyMap(), // title (underscored, decoded prefix) -> response json
    val articleSearch: String = wpSearchJson(),
) {
    val http = FakeHttp { url ->
        when {
            "action=wbsearchentities" in url -> {
                val term = java.net.URLDecoder.decode(Regex("search=([^&]*)").find(url)!!.groupValues[1], "UTF-8")
                ok(entitySearch[term] ?: searchJson())
            }
            "action=wbgetentities" in url -> {
                val ids = Regex("ids=([^&]*)").find(url)!!.groupValues[1].split("|")
                ok(entitiesJson(*ids.mapNotNull { db[it] }.toTypedArray()))
            }
            "rest_v1/page/summary/" in url -> {
                val t = java.net.URLDecoder.decode(url.substringAfter("summary/").substringBefore("?"), "UTF-8")
                summaries[t]?.let { ok(it) } ?: HttpResponse(404, "{}")
            }
            "list=search" in url -> ok(articleSearch)
            else -> HttpResponse(404, "{}")
        }
    }
}

/** Shared Queen / Bohemian Rhapsody fixture used by UI-flow tests. */
object QueenWorld {
    fun build(): FakeWiki {
        val queen = entityJson(
            "Q15862", "Queen", "British rock band",
            claims = mapOf(
                "P31" to listOf("Q5741069"), "P434" to listOf("mbid"), "P136" to listOf("Q11399"),
                "P571" to listOf(com.mundymorning.touchmusicid.knowledge.ClaimValue.Time("+1970-00-00T00:00:00Z", 9)), "P740" to listOf("Q84"),
            ),
            enwiki = "Queen (band)",
        )
        val song = entityJson(
            "Q187655", "Bohemian Rhapsody", "1975 song by Queen",
            claims = mapOf(
                "P31" to listOf("Q7366"), "P175" to listOf("Q15862"), "P361" to listOf("Q207"),
                "P577" to listOf(com.mundymorning.touchmusicid.knowledge.ClaimValue.Time("+1975-10-31T00:00:00Z", 11)),
            ),
            enwiki = "Bohemian Rhapsody",
        )
        return FakeWiki(
            db = listOf(queen, song, entityJson("Q11399", "rock music"), entityJson("Q84", "London"), entityJson("Q207", "A Night at the Opera")).associateBy { it.getString("id") },
            entitySearch = mapOf(
                "Queen" to searchJson(Triple("Q15862", "Queen", "British rock band")),
                "Bohemian Rhapsody" to searchJson(Triple("Q187655", "Bohemian Rhapsody", "1975 song by Queen")),
            ),
            summaries = mapOf(
                "Queen_(band)" to summaryJson("Queen (band)", "Queen are a British rock band formed in London in 1970. Their classic line-up was Freddie Mercury, Brian May, Roger Taylor and John Deacon.", "British rock band"),
                "Bohemian_Rhapsody" to summaryJson("Bohemian Rhapsody", "\"Bohemian Rhapsody\" is a song by the British rock band Queen. It was written by Freddie Mercury for the band's 1975 album A Night at the Opera."),
            ),
        )
    }
}
