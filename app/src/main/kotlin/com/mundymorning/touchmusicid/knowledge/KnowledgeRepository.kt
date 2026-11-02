package com.mundymorning.touchmusicid.knowledge

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.domain.normalizeText
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Cache-first knowledge lookup with graceful offline behaviour.
 *  - Fresh cache hit: no network.
 *  - Otherwise fetch live and store.
 *  - Network failure with an older cached copy: return the stale copy, flagged so the UI can say so.
 */
class KnowledgeRepository(
    private val resolver: KnowledgeResolver,
    private val cache: KnowledgeCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshMillis: Long = 7L * DAY,
    private val negativeFreshMillis: Long = DAY,
) {
    suspend fun load(song: Song, forceRefresh: Boolean = false): KnowledgeResult {
        val key = cacheKey(song)
        val cached = cache.get(key)?.let { (payload, at) -> BundleCodec.decode(payload)?.let { it to at } }
        if (cached != null && !forceRefresh) {
            val (bundle, at) = cached
            val ttl = if (bundle.artist.status == MatchStatus.VERIFIED && bundle.song.status != MatchStatus.AMBIGUOUS) freshMillis else negativeFreshMillis
            if (clock() - at < ttl) return KnowledgeResult.Found(bundle, Freshness.CACHE_FRESH)
        }
        return fetch(song, key, cached?.first, chosenArtistQid(song))
    }

    /** User resolved an ambiguous artist. The choice is remembered for this song's artist name. */
    suspend fun chooseArtist(song: Song, qid: String): KnowledgeResult {
        cache.put(choiceKey(song), qid, clock())
        return fetch(song, cacheKey(song), null, qid)
    }

    private suspend fun chosenArtistQid(song: Song): String? = cache.get(choiceKey(song))?.first

    private suspend fun fetch(song: Song, key: String, stale: KnowledgeBundle?, chosen: String?): KnowledgeResult = try {
        val (artist, songLookup) = resolver.resolve(song, chosen)
        val bundle = KnowledgeBundle(artist, songLookup, clock())
        cache.put(key, BundleCodec.encode(bundle), bundle.fetchedAtMillis)
        KnowledgeResult.Found(bundle, Freshness.LIVE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RateLimitedException) {
        stale?.let { KnowledgeResult.Found(it, Freshness.CACHE_STALE_OFFLINE) } ?: KnowledgeResult.Failed(KnowledgeResult.Kind.RATE_LIMITED)
    } catch (e: ServerException) {
        stale?.let { KnowledgeResult.Found(it, Freshness.CACHE_STALE_OFFLINE) } ?: KnowledgeResult.Failed(KnowledgeResult.Kind.SERVER)
    } catch (e: IOException) {
        stale?.let { KnowledgeResult.Found(it, Freshness.CACHE_STALE_OFFLINE) } ?: KnowledgeResult.Failed(KnowledgeResult.Kind.OFFLINE)
    } catch (e: Exception) {
        KnowledgeResult.Failed(KnowledgeResult.Kind.UNKNOWN)
    }

    private fun cacheKey(song: Song) = "k1|" + song.key
    private fun choiceKey(song: Song) = "choice|" + normalizeText(song.artist)

    companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
