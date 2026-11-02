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
