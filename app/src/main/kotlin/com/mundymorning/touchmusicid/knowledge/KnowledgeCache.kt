package com.mundymorning.touchmusicid.knowledge

import org.json.JSONArray
import org.json.JSONObject

interface KnowledgeCache {
    suspend fun get(key: String): Pair<String, Long>?   // payload, fetchedAtMillis
    suspend fun put(key: String, payload: String, fetchedAtMillis: Long)
    suspend fun clear()
}

class InMemoryKnowledgeCache : KnowledgeCache {
    val map = HashMap<String, Pair<String, Long>>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, payload: String, fetchedAtMillis: Long) { map[key] = payload to fetchedAtMillis }
    override suspend fun clear() = map.clear()
}

/** JSON round-trip for [KnowledgeBundle], stored in the cache table. */
object BundleCodec {
    fun encode(b: KnowledgeBundle): String = JSONObject().apply {
        put("v", 1); put("at", b.fetchedAtMillis); put("artist", lookup(b.artist)); put("song", lookup(b.song))
    }.toString()

    fun decode(s: String): KnowledgeBundle? = try {
        val o = JSONObject(s)
        if (o.optInt("v") != 1) null else KnowledgeBundle(lookup(o.getJSONObject("artist")), lookup(o.getJSONObject("song")), o.getLong("at"))
    } catch (e: org.json.JSONException) { null }

    private fun lookup(l: Lookup) = JSONObject().apply {
        put("status", l.status.name)
        l.entity?.let { put("entity", entity(it)) }
        put("candidates", JSONArray(l.candidates.map { JSONObject().put("qid", it.qid).put("label", it.label).put("desc", it.description) }))
    }

    private fun lookup(o: JSONObject) = Lookup(
        MatchStatus.valueOf(o.getString("status")),
        o.optJSONObject("entity")?.let(::entity),
        o.optJSONArray("candidates")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Candidate(it.getString("qid"), it.getString("label"), it.optStr("desc")) }
        }.orEmpty(),
    )

    private fun entity(e: EntityKnowledge) = JSONObject().apply {
        put("qid", e.qid); put("name", e.name); put("desc", e.description); put("extract", e.extract)
        put("wp", e.wikipediaUrl); put("wd", e.wikidataUrl); put("basis", e.basis)
        put("facts", JSONArray(e.facts.map { JSONObject().put("l", it.label).put("v", it.value).put("s", it.source.name).put("u", it.url) }))
    }

    private fun entity(o: JSONObject) = EntityKnowledge(
        qid = o.optStr("qid"), name = o.getString("name"), description = o.optStr("desc"), extract = o.optStr("extract"),
        wikipediaUrl = o.optStr("wp"), wikidataUrl = o.optStr("wd"), basis = o.optString("basis"),
        facts = o.optJSONArray("facts")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Fact(it.getString("l"), it.getString("v"), FactSource.valueOf(it.getString("s")), it.optStr("u")) }
        }.orEmpty(),
    )

    private fun JSONObject.optStr(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotEmpty() }
}
