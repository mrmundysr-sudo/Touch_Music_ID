package com.mundymorning.touchmusicid.knowledge

import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON parsers for the Wikipedia REST/Action APIs and Wikidata. No I/O, so they are unit-tested directly. */

data class ArticleSummary(
    val title: String,
    val description: String?,
    val extract: String?,
    val url: String?,
    val isDisambiguation: Boolean,
)

data class SearchHit(val title: String, val snippet: String?)
data class EntityHit(val id: String, val label: String?, val description: String?)

sealed interface ClaimValue {
    data class Ref(val id: String) : ClaimValue
    data class Time(val iso: String, val precision: Int) : ClaimValue
    data class Text(val value: String) : ClaimValue
}

data class WikidataEntity(
    val id: String,
    val label: String?,
    val description: String?,
    val aliases: List<String>,
    val claims: Map<String, List<ClaimValue>>,
    val enwikiTitle: String?,
) {
    fun refs(property: String): List<String> = claims[property].orEmpty().filterIsInstance<ClaimValue.Ref>().map { it.id }
    fun has(property: String): Boolean = !claims[property].isNullOrEmpty()
    fun firstTime(property: String): ClaimValue.Time? = claims[property].orEmpty().filterIsInstance<ClaimValue.Time>().firstOrNull()
    val names: List<String> get() = listOfNotNull(label) + aliases
}

object WikiParsers {
    fun parseSummary(json: String): ArticleSummary? = try {
        val o = JSONObject(json)
        val type = o.optString("type")
        val title = o.optString("title").ifBlank { return null }
        ArticleSummary(
            title = title,
            description = o.optStringOrNull("description"),
            extract = o.optStringOrNull("extract"),
            url = o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optStringOrNull("page"),
            isDisambiguation = type == "disambiguation",
        )
    } catch (e: org.json.JSONException) {
        null
    }

    fun parseSearch(json: String): List<SearchHit> = try {
        val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            SearchHit(it.getString("title"), it.optStringOrNull("snippet")?.replace(Regex("<[^>]+>"), ""))
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    fun parseEntitySearch(json: String): List<EntityHit> = try {
        val arr = JSONObject(json).optJSONArray("search") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            EntityHit(it.getString("id"), it.optStringOrNull("label"), it.optStringOrNull("description"))
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    fun parseEntities(json: String): Map<String, WikidataEntity> = try {
        val ents = JSONObject(json).optJSONObject("entities") ?: JSONObject()
        ents.keys().asSequence().mapNotNull { id ->
            val e = ents.getJSONObject(id)
            if (e.has("missing")) return@mapNotNull null
            id to WikidataEntity(
                id = id,
                label = e.optJSONObject("labels")?.optJSONObject("en")?.optStringOrNull("value"),
                description = e.optJSONObject("descriptions")?.optJSONObject("en")?.optStringOrNull("value"),
                aliases = e.optJSONObject("aliases")?.optJSONArray("en")?.let { a ->
                    (0 until a.length()).mapNotNull { a.getJSONObject(it).optStringOrNull("value") }
                }.orEmpty(),
                claims = parseClaims(e.optJSONObject("claims")),
                enwikiTitle = e.optJSONObject("sitelinks")?.optJSONObject("enwiki")?.optStringOrNull("title"),
            )
        }.toMap()
    } catch (e: org.json.JSONException) {
        emptyMap()
    }

    private fun parseClaims(claims: JSONObject?): Map<String, List<ClaimValue>> {
        if (claims == null) return emptyMap()
        val out = LinkedHashMap<String, List<ClaimValue>>()
        for (prop in claims.keys()) {
            val statements = claims.optJSONArray(prop) ?: continue
            val values = (0 until statements.length()).mapNotNull { i ->
                val st = statements.optJSONObject(i) ?: return@mapNotNull null
                if (st.optString("rank") == "deprecated") return@mapNotNull null
                val dv = st.optJSONObject("mainsnak")?.optJSONObject("datavalue") ?: return@mapNotNull null
                when (dv.optString("type")) {
                    "wikibase-entityid" -> dv.optJSONObject("value")?.optStringOrNull("id")?.let { ClaimValue.Ref(it) }
                    "time" -> dv.optJSONObject("value")?.let {
                        ClaimValue.Time(it.optString("time"), it.optInt("precision", 11))
                    }
                    "string" -> dv.optStringOrNull("value")?.let { ClaimValue.Text(it) }
                    else -> null
                }
            }
            if (values.isNotEmpty()) out[prop] = values
        }
        return out
    }

    fun parseLabels(json: String): Map<String, String> = parseEntities(json).mapNotNull { (id, e) -> e.label?.let { id to it } }.toMap()

    /** "+1946-09-05T00:00:00Z" at precision 11 -> "1946-09-05"; precision 10 -> "1946-09"; 9 or less -> "1946". */
    fun formatTime(t: ClaimValue.Time): String? {
        val m = Regex("^([+-])(\\d+)-(\\d{2})-(\\d{2})T").find(t.iso) ?: return null
        val (sign, y, mo, d) = m.destructured
        val year = y.trimStart('0').ifEmpty { "0" }
        val core = when {
            t.precision >= 11 && mo != "00" && d != "00" -> "$year-$mo-$d"
            t.precision == 10 && mo != "00" -> "$year-$mo"
            else -> year
        }
        return if (sign == "-") "$core BCE" else core
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
}
