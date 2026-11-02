package com.mundymorning.touchmusicid.knowledge

import com.mundymorning.touchmusicid.net.HttpFetcher
import java.io.IOException
import java.net.URLEncoder

class RateLimitedException(val retryAfterSeconds: Long?) : IOException("Rate limited")
class ServerException(val code: Int) : IOException("HTTP $code")

/** Thin, polite client over the official Wikipedia and Wikidata endpoints. Throws IOException subclasses on failure. */
class WikiApi(
    private val http: HttpFetcher,
    private val wikipediaHost: String = "en.wikipedia.org",
    private val wikidataHost: String = "www.wikidata.org",
) {
    suspend fun summary(title: String): ArticleSummary? {
        val path = enc(title.replace(' ', '_')).replace("+", "%20")
        val r = http.get("https://$wikipediaHost/api/rest_v1/page/summary/$path?redirect=true")
        if (r.code == 404) return null
        return WikiParsers.parseSummary(checked(r).body)
    }

    suspend fun searchArticles(query: String, limit: Int = 5): List<SearchHit> {
        val url = "https://$wikipediaHost/w/api.php?action=query&list=search&srsearch=${enc(query)}" +
            "&srlimit=$limit&srnamespace=0&srprop=snippet&format=json&formatversion=2&utf8=1"
        return WikiParsers.parseSearch(checked(http.get(url)).body)
    }

    suspend fun searchEntities(name: String, limit: Int = 7): List<EntityHit> {
        val url = "https://$wikidataHost/w/api.php?action=wbsearchentities&search=${enc(name)}" +
            "&language=en&uselang=en&type=item&limit=$limit&format=json"
        return WikiParsers.parseEntitySearch(checked(http.get(url)).body)
    }

    suspend fun entities(ids: Collection<String>, full: Boolean = true): Map<String, WikidataEntity> {
        if (ids.isEmpty()) return emptyMap()
        val props = if (full) "labels|descriptions|aliases|claims|sitelinks" else "labels"
        val url = "https://$wikidataHost/w/api.php?action=wbgetentities&ids=${ids.distinct().take(50).joinToString("|")}" +
            "&props=${enc(props)}&languages=en&sitefilter=enwiki&format=json"
        return WikiParsers.parseEntities(checked(http.get(url)).body)
    }

    private fun checked(r: com.mundymorning.touchmusicid.net.HttpResponse): com.mundymorning.touchmusicid.net.HttpResponse {
        if (r.code == 429) throw RateLimitedException(r.retryAfterSeconds)
        if (r.code !in 200..299) throw ServerException(r.code)
        return r
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
