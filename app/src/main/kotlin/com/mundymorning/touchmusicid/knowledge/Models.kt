package com.mundymorning.touchmusicid.knowledge

enum class FactSource(val label: String) { WIKIPEDIA("Wikipedia"), WIKIDATA("Wikidata") }

/** A single sourced statement. Nothing in the UI is shown without one of these or a quoted Wikipedia extract. */
data class Fact(val label: String, val value: String, val source: FactSource, val url: String?)

data class SourceLink(val label: String, val url: String)

enum class MatchStatus {
    /** Exactly one candidate passed all checks. */
    VERIFIED,
    /** More than one plausible candidate; the user must choose. Nothing is asserted. */
    AMBIGUOUS,
    /** No candidate passed the checks. Nothing is asserted. */
    NONE,
}

data class Candidate(val qid: String, val label: String, val description: String?)

data class EntityKnowledge(
    val qid: String?,
    val name: String,
    val description: String?,
    /** Verbatim Wikipedia lead extract (CC BY-SA 4.0). Never paraphrased by the app. */
    val extract: String?,
    val wikipediaUrl: String?,
    val wikidataUrl: String?,
    val facts: List<Fact>,
    /** Human-readable reason this entity was accepted, e.g. "Wikidata item with matching name and a MusicBrainz ID". */
    val basis: String,
)

data class Lookup(
    val status: MatchStatus,
    val entity: EntityKnowledge? = null,
    val candidates: List<Candidate> = emptyList(),
)

data class KnowledgeBundle(
    val artist: Lookup,
    val song: Lookup,
    val fetchedAtMillis: Long,
) {
    fun sources(): List<SourceLink> = buildList {
        for (e in listOfNotNull(artist.entity, song.entity)) {
            e.wikipediaUrl?.let { add(SourceLink("Wikipedia: ${e.name}", it)) }
            e.wikidataUrl?.let { add(SourceLink("Wikidata: ${e.name}", it)) }
        }
    }.distinctBy { it.url }
}

enum class Freshness { LIVE, CACHE_FRESH, CACHE_STALE_OFFLINE }

sealed interface KnowledgeResult {
    data class Found(val bundle: KnowledgeBundle, val freshness: Freshness) : KnowledgeResult
    data class Failed(val kind: Kind) : KnowledgeResult
    enum class Kind { OFFLINE, RATE_LIMITED, SERVER, UNKNOWN }
}

const val ATTRIBUTION_WIKIPEDIA =
    "Text from Wikipedia is available under the Creative Commons Attribution-ShareAlike 4.0 license; " +
        "see each article's page history for authors."
const val ATTRIBUTION_WIKIDATA = "Structured data from Wikidata is available under CC0."
