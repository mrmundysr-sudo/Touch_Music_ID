package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.net.HttpFetcher
import org.json.JSONObject

/** A short-lived Apple developer token (JWT) minted by YOUR backend. The app never holds the private key. */
data class DeveloperToken(val value: String, val expiresAtEpochSeconds: Long)

sealed interface TokenResult {
    data class Ok(val token: DeveloperToken) : TokenResult
    data object NotConfigured : TokenResult
    data class Failed(val message: String) : TokenResult
}

interface TokenSource {
    val isConfigured: Boolean
    suspend fun token(): TokenResult
}

/**
 * Fetches `{"token": "<jwt>", "expiresAt": <epoch seconds>}` from an operator-controlled HTTPS endpoint
 * (reference design: docs/TOKEN_SERVICE.md). HTTPS only. The token is cached in memory only, never persisted,
 * and refreshed shortly before expiry.
 */
class RemoteTokenSource(
    private val url: String,
    private val http: HttpFetcher,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refreshMarginSeconds: Long = 60,
) : TokenSource {
    private var cached: DeveloperToken? = null

    override val isConfigured: Boolean get() = url.startsWith("https://")

    override suspend fun token(): TokenResult {
        if (!isConfigured) return TokenResult.NotConfigured
        cached?.let { if (it.expiresAtEpochSeconds - refreshMarginSeconds > nowSeconds()) return TokenResult.Ok(it) }
        return try {
            val response = http.get(url)
            if (response.code !in 200..299) return TokenResult.Failed("Token service returned HTTP ${response.code}")
            val json = JSONObject(response.body)
            val value = json.optString("token")
            val expires = json.optLong("expiresAt", 0)
            if (value.isBlank() || expires <= nowSeconds()) return TokenResult.Failed("Token service returned an invalid or expired token")
            DeveloperToken(value, expires).also { cached = it }.let { TokenResult.Ok(it) }
        } catch (e: java.io.IOException) {
            TokenResult.Failed("Could not reach token service")
        } catch (e: org.json.JSONException) {
            TokenResult.Failed("Token service returned malformed JSON")
        }
    }
}
