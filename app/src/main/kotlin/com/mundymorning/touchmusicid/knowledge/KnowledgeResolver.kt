package com.mundymorning.touchmusicid.knowledge

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.domain.normalizeText

/**
 * Conservative entity resolution. The rule is: assert nothing unless a checkable signal supports it.
 *  - Artist: Wikidata item whose label/alias equals the name AND that is demonstrably a musical entity.
 *    Exactly one such item => VERIFIED. Several => AMBIGUOUS (user chooses). None => NONE.
 *  - Song: Wikidata item whose label equals the title, that is a musical work, and whose performer matches the
 *    recognised artist. Falls back to a Wikipedia article only if its text names the artist.
 * Facts shown come from Wikidata statements or the verbatim Wikipedia extract, each with a source.
 */
class KnowledgeResolver(private val api: WikiApi) {

    suspend fun resolve(song: Song, chosenArtistQid: String? = null): Pair<Lookup, Lookup> {
        val artist = resolveArtist(song.artist, chosenArtistQid)
        val songLookup = resolveSong(song, artist)
        return artist to songLookup
    }

    // ---- Artist ---------------------------------------------------------------------------------------------

    suspend fun resolveArtist(name: String, chosenQid: String? = null): Lookup {
        if (name.isBlank()) return Lookup(MatchStatus.NONE)
        val ids: List<String> = if (chosenQid != null) listOf(chosenQid) else api.searchEntities(name).map { it.id }
        if (ids.isEmpty()) return Lookup(MatchStatus.NONE)
        val entities = api.entities(ids)
        val qualified = ids.mapNotNull { entities[it] }
            .filter { chosenQid != null || nameMatches(it, name) }
            .filter { isMusicalEntity(it) }
        return when {
            qualified.isEmpty() -> Lookup(MatchStatus.NONE)
            qualified.size > 1 -> Lookup(
                MatchStatus.AMBIGUOUS,
                candidates = qualified.map { Candidate(it.id, it.label ?: name, it.description) },
            )
            else -> Lookup(MatchStatus.VERIFIED, entity = buildEntity(qualified.single(), artistBasis(qualified.single()), isArtist = true))
        }
    }

    internal fun nameMatches(e: WikidataEntity, name: String): Boolean {
        val target = normalizeText(name)
        return e.names.any { normalizeText(it) == target }
    }

    internal fun isMusicalEntity(e: WikidataEntity): Boolean {
        if (e.has(P_MUSICBRAINZ_ARTIST) || e.has(P_SPOTIFY_ARTIST) || e.has(P_ALLMUSIC_ARTIST)) return true
        val isHuman = Q_HUMAN in e.refs(P_INSTANCE_OF)
        if (isHuman && e.refs(P_OCCUPATION).any { it in MUSIC_OCCUPATIONS }) return true
        return e.refs(P_INSTANCE_OF).any { it in MUSIC_GROUP_CLASSES }
    }

    private fun artistBasis(e: WikidataEntity): String = when {
        e.has(P_MUSICBRAINZ_ARTIST) -> "Wikidata item whose name matches exactly and which carries a MusicBrainz artist ID."
        else -> "Wikidata item whose name matches exactly and which is a musician or musical group."
    }

    // ---- Song -----------------------------------------------------------------------------------------------

    suspend fun resolveSong(song: Song, artist: Lookup): Lookup {
        val hits = api.searchEntities(song.title, limit = 8)
        val entities = if (hits.isEmpty()) emptyMap() else api.entities(hits.map { it.id })
        val works = hits.mapNotNull { entities[it.id] }
            .filter { nameMatches(it, song.title) && isMusicalWork(it) }
        val performerLabels = labelsFor(works.flatMap { it.refs(P_PERFORMER) })
        val artistQid = artist.entity?.qid
        val withArtist = works.filter { w ->
            w.refs(P_PERFORMER).any { p ->
                p == artistQid || performerLabels[p]?.let { normalizeText(it) == normalizeText(song.artist) } == true
            }
        }
        val chosen = when {
            withArtist.size == 1 -> withArtist.single()
            withArtist.size > 1 -> withArtist.filter { it.enwikiTitle != null }.singleOrNull()
            else -> null
        }
        if (chosen != null) {
            return Lookup(MatchStatus.VERIFIED, buildEntity(chosen, "Wikidata musical work with a matching title and performer.", isArtist = false, songArtist = song.artist))
        }
        if (withArtist.size > 1) return Lookup(MatchStatus.AMBIGUOUS)
        return wikipediaOnlySong(song)
    }

    internal fun isMusicalWork(e: WikidataEntity): Boolean =
        e.refs(P_INSTANCE_OF).any { it in MUSIC_WORK_CLASSES } || e.has(P_PERFORMER)

    private suspend fun wikipediaOnlySong(song: Song): Lookup {
        val title = normalizeText(song.title)
        val artist = normalizeText(song.artist)
        val hits = api.searchArticles("\"${song.title}\" ${song.artist} song", limit = 5)
        for (hit in hits) {
            if (!normalizeText(hit.title).contains(title)) continue
            val s = api.summary(hit.title) ?: continue
            if (s.isDisambiguation) continue
            val text = normalizeText((s.description.orEmpty()) + " " + s.extract.orEmpty())
            if (!text.contains(artist)) continue
            return Lookup(
                MatchStatus.VERIFIED,
                EntityKnowledge(
                    qid = null, name = s.title, description = s.description, extract = s.extract,
                    wikipediaUrl = s.url, wikidataUrl = null, facts = emptyList(),
                    basis = "Wikipedia article whose title contains the song title and whose text names the artist. No Wikidata record was confirmed.",
                ),
            )
        }
        return Lookup(MatchStatus.NONE)
    }

    // ---- Building entities ----------------------------------------------------------------------------------

    private suspend fun labelsFor(ids: Collection<String>): Map<String, String> =
        if (ids.isEmpty()) emptyMap() else api.entities(ids, full = false).mapNotNull { (k, v) -> v.label?.let { k to it } }.toMap()

    private suspend fun buildEntity(e: WikidataEntity, basis: String, isArtist: Boolean, songArtist: String? = null): EntityKnowledge {
        val refIds = FACT_REF_PROPS.flatMap { e.refs(it) }.distinct()
        val labels = labelsFor(refIds)
        val wdUrl = "https://www.wikidata.org/wiki/${e.id}"
        val facts = ArrayList<Fact>()
        fun refFact(label: String, prop: String, max: Int = 4) {
            val v = e.refs(prop).mapNotNull { labels[it] }.distinct().take(max)
            if (v.isNotEmpty()) facts += Fact(label, v.joinToString(", "), FactSource.WIKIDATA, wdUrl)
        }
        fun timeFact(label: String, prop: String) {
            e.firstTime(prop)?.let { WikiParsers.formatTime(it) }?.let { facts += Fact(label, it, FactSource.WIKIDATA, wdUrl) }
        }
        if (isArtist) {
            timeFact("Born", P_BIRTH); timeFact("Formed", P_INCEPTION); timeFact("Died", P_DEATH); timeFact("Disbanded", P_DISSOLVED)
            refFact("Origin", P_BIRTHPLACE, 1); refFact("Formed in", P_FORMATION_LOCATION, 1)
            refFact("Occupation", P_OCCUPATION); refFact("Genre", P_GENRE); refFact("Record label", P_LABEL, 3)
        } else {
            timeFact("Released", P_PUBLICATION); refFact("Album / release", P_PART_OF, 2); refFact("Genre", P_GENRE)
            refFact("Record label", P_LABEL, 3); refFact("Producer", P_PRODUCER, 3); refFact("Composer", P_COMPOSER, 3)
            refFact("Lyricist", P_LYRICIST, 3)
        }
        // A person's birthplace and "formed in" are often the same place; show it once.
        val origin = facts.firstOrNull { it.label == "Origin" }?.value
        facts.removeAll { it.label == "Formed in" && it.value == origin }
        val summary = e.enwikiTitle?.let { api.summary(it) }?.takeUnless { it.isDisambiguation }
        return EntityKnowledge(
            qid = e.id,
            name = e.label ?: summary?.title ?: songArtist.orEmpty(),
            description = e.description ?: summary?.description,
            extract = summary?.extract,
            wikipediaUrl = summary?.url,
            wikidataUrl = wdUrl,
            facts = facts,
            basis = basis,
        )
    }

    companion object {
        const val P_INSTANCE_OF = "P31"; const val P_OCCUPATION = "P106"; const val P_GENRE = "P136"
        const val P_PERFORMER = "P175"; const val P_PART_OF = "P361"; const val P_LABEL = "P264"
        const val P_PRODUCER = "P162"; const val P_COMPOSER = "P86"; const val P_LYRICIST = "P676"
        const val P_PUBLICATION = "P577"; const val P_BIRTH = "P569"; const val P_DEATH = "P570"
        const val P_INCEPTION = "P571"; const val P_DISSOLVED = "P576"
        const val P_BIRTHPLACE = "P19"; const val P_FORMATION_LOCATION = "P740"
        const val P_MUSICBRAINZ_ARTIST = "P434"; const val P_SPOTIFY_ARTIST = "P1902"; const val P_ALLMUSIC_ARTIST = "P1728"
        const val Q_HUMAN = "Q5"
        val FACT_REF_PROPS = listOf(P_OCCUPATION, P_GENRE, P_PART_OF, P_LABEL, P_PRODUCER, P_COMPOSER, P_LYRICIST, P_BIRTHPLACE, P_FORMATION_LOCATION)

        /** singer, musician, composer, songwriter, record producer, rapper, DJ, guitarist, pianist, conductor, drummer, bassist. */
        val MUSIC_OCCUPATIONS = setOf(
            "Q177220", "Q639669", "Q36834", "Q753110", "Q183945", "Q2252262", "Q130857",
            "Q855091", "Q486748", "Q158852", "Q386854", "Q584301", "Q488205", "Q806349",
        )

        /** musical group, band, rock band, musical duo, musical ensemble, vocal group, girl group, boy band. */
        val MUSIC_GROUP_CLASSES = setOf(
            "Q215380", "Q5741069", "Q56816954", "Q2088357", "Q1043271", "Q641066", "Q216337", "Q16010345",
        )

        /** song, single, musical work, musical composition, album, music track. */
        val MUSIC_WORK_CLASSES = setOf(
            "Q7366", "Q134556", "Q2188189", "Q105543609", "Q482994", "Q4176309", "Q108352648",
        )
    }
}
