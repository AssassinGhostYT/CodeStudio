package dev.ide.agent.impl

import dev.ide.agent.TokenSource
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Claude Code OAuth flow: sign in with a claude.ai subscription (Pro/Max) instead of a console API key.
 *
 * The endpoint is Anthropic's own copy/paste authorisation — no local redirect listener is needed. The app
 * builds an authorization URL with a PKCE challenge, opens it in the platform browser, and the user pastes
 * the code the page shows back into the app; the code is then exchanged for an access + refresh token pair
 * at Anthropic's token endpoint.
 *
 * The resulting access token is an `sk-ant-oat…` credential and authenticates `POST /v1/messages` as a
 * subscription session rather than a metered API key (see [AnthropicProvider], which switches both the auth
 * header and the required OAuth beta flags on that prefix).
 */
object ClaudeOAuth {
    /** Claude Code's public OAuth client. A public client, so there is no client secret — PKCE replaces it. */
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
    const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"

    /** Where the browser lands; with `code=true` it renders the code for the user to copy instead of redirecting. */
    const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"

    const val SCOPES = "org:create_api_key user:profile user:inference"

    /** Prefix of an OAuth access token (as opposed to a `sk-ant-api…` console key). */
    const val ACCESS_TOKEN_PREFIX = "sk-ant-oat"

    private const val PKCE_VERIFIER_BYTES = 32
    private const val STATE_BYTES = 32

    private val random = SecureRandom()

    /** True when [credential] is a subscription access token rather than a console API key. */
    fun isAccessToken(credential: String): Boolean = credential.startsWith(ACCESS_TOKEN_PREFIX)

    /** The PKCE pair plus `state` backing one sign-in attempt; kept between the URL and the code exchange. */
    data class Pending(
        val verifier: String,
        val state: String,
        /** When the authorization URL was built — the code the user pastes is only valid for ten minutes. */
        val issuedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - issuedAtMs > AUTH_CODE_TTL_MS

        companion object {
            const val AUTH_CODE_TTL_MS = 10 * 60 * 1000L
        }
    }

    /** A fresh sign-in attempt: generate the verifier, derive its S256 challenge, and mint a `state`. */
    fun begin(): Pending = Pending(verifier = base64Url(randomBytes(PKCE_VERIFIER_BYTES)), state = base64Url(randomBytes(STATE_BYTES)))

    /**
     * The URL to open in the browser. `code=true` is Anthropic's copy/paste mode: the consent page ends on a
     * screen that shows the authorization code (and `state`) for the user to bring back to the app, instead of
     * requiring a reachable redirect target.
     */
    fun authorizeUrl(pending: Pending): String = buildString {
        append(AUTHORIZE_URL)
        append('?')
        append("code=true")
        append('&').append("client_id=").append(enc(CLIENT_ID))
        append('&').append("response_type=code")
        append('&').append("redirect_uri=").append(enc(REDIRECT_URI))
        append('&').append("scope=").append(enc(SCOPES))
        append('&').append("code_challenge=").append(enc(sha256Base64Url(pending.verifier)))
        append('&').append("code_challenge_method=S256")
        append('&').append("state=").append(enc(pending.state))
    }

    /** The tokens returned by a successful exchange or refresh. */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        /** Epoch millis at which [accessToken] stops working; 0 when the server did not say. */
        val expiresAtMs: Long,
        val scopes: List<String>,
    )

    /**
     * Exchanges the pasted authorization code for tokens. [code] may arrive as `CODE#STATE` or `CODE&state=…`
     * from the browser — both are unwrapped, and a `state` carried in the paste wins over [pending]'s.
     */
    suspend fun exchange(transport: LlmTransport, pending: Pending, code: String): Tokens {
        val (clean, pastedState) = splitPastedCode(code)
        return requestTokens(
            transport,
            buildMap {
                put("grant_type", "authorization_code")
                put("client_id", CLIENT_ID)
                put("code", clean)
                put("redirect_uri", REDIRECT_URI)
                put("code_verifier", pending.verifier)
                put("state", pastedState ?: pending.state)
            },
        )
    }

    /** Exchanges a refresh token for a new access token. */
    suspend fun refresh(transport: LlmTransport, refreshToken: String): Tokens = requestTokens(
        transport,
        mapOf(
            "grant_type" to "refresh_token",
            "client_id" to CLIENT_ID,
            "refresh_token" to refreshToken,
        ),
    )

    /**
     * The token endpoint takes a JSON body (not the usual form encoding) and expects the request to look like
     * it came from claude.ai — hence the origin/referer headers.
     */
    private suspend fun requestTokens(transport: LlmTransport, body: Map<String, String>): Tokens {
        val json = buildString {
            append('{')
            var first = true
            body.forEach { (k, v) ->
                if (!first) append(',')
                first = false
                append(jsonString(k)).append(':').append(jsonString(v))
            }
            append('}')
        }
        val raw = transport.post(
            TOKEN_URL,
            mapOf(
                "content-type" to "application/json",
                "accept" to "application/json, text/plain, */*",
                "origin" to "https://claude.ai",
                "referer" to "https://claude.ai/",
                "user-agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            ),
            json,
        )
        return parseTokens(raw)
    }

    /** Reads an OAuth token response (or surfaces the endpoint's error message as an [IllegalStateException]). */
    internal fun parseTokens(raw: String): Tokens {
        val obj = AgentJson.parseToJsonElement(raw).asObj()
            ?: throw IllegalStateException("Claude sign-in: unreadable response from the token endpoint")
        obj["error"]?.let { err ->
            val detail = err.asObj()?.get("error_description")?.asStr() ?: err.asStr()
            throw IllegalStateException(detail ?: "Claude sign-in failed")
        }
        val access = obj["access_token"].asStr()
            ?: throw IllegalStateException("Claude sign-in: no access token in the response")
        val expiresInSeconds = obj["expires_in"].asStr()?.toLongOrNull() ?: obj["expires_in"].asInt()?.toLong() ?: 0L
        return Tokens(
            accessToken = access,
            refreshToken = obj["refresh_token"].asStr(),
            expiresAtMs = if (expiresInSeconds > 0) System.currentTimeMillis() + expiresInSeconds * 1000 else 0L,
            scopes = obj["scope"].asStr()?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    /** The pasted value is `CODE`, `CODE#STATE`, or `CODE&state=…`; returns (code, state?). */
    private fun splitPastedCode(raw: String): Pair<String, String?> {
        var text = raw.trim().removeSurrounding("\"", "'")
        var state: String? = null
        if ('#' in text) {
            val (code, fragment) = text.split('#', limit = 2)
            state = fragment.substringAfter("state=", "").takeIf { it.isNotBlank() } ?: fragment.takeIf { it.isNotBlank() }
            text = code
        }
        if ("state=" in text) {
            val (code, rest) = text.split('&', limit = 2)
            val st = rest.substringAfter("state=", "")
            if (st.isNotBlank()) state = st
            text = code
        }
        // Drop any trailing query params the page appended to the code itself.
        text = text.substringBefore('&').substringBefore('#')
        return text.trim() to state
    }

    private fun sha256Base64Url(value: String): String = base64Url(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)),
    )

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/**
 * A [TokenSource] for Anthropic's subscription login: returns the stored access token, refreshing it shortly
 * before it expires. The refresh is serialised so an agent loop (which asks for a token on every step) cannot
 * fire a stampede of refreshes, and a failed refresh falls through to the last known token so the request at
 * least reports the API's own error instead of a local one.
 */
class ClaudeTokenSource(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val transport: LlmTransport,
    private val refreshKey: String = "anthropicRefresh",
    private val expiryKey: String = "anthropicExpiresAt",
    private val accessKey: String = "anthropicKey",
) : TokenSource {
    /** Serialises refreshes: an agent loop asks for a token on every step, and they must not stampede. */
    private val lock = Mutex()

    override suspend fun token(): String? {
        val access = read(accessKey)?.takeIf { it.isNotBlank() } ?: return null
        if (!ClaudeOAuth.isAccessToken(access)) return access
        if (!isNearExpiry()) return access

        val refreshToken = read(refreshKey)?.takeIf { it.isNotBlank() } ?: return access
        lock.withLock {
            // Re-check under the lock: another request may have refreshed while this one waited.
            val current = read(accessKey) ?: return access
            if (!isNearExpiry()) return current
            return runCatching { ClaudeOAuth.refresh(transport, refreshToken) }.fold(
                onSuccess = { tokens ->
                    write(accessKey, tokens.accessToken)
                    tokens.refreshToken?.let { write(refreshKey, it) }
                    write(expiryKey, tokens.expiresAtMs.toString())
                    tokens.accessToken
                },
                // A failed refresh falls through to the last known token so the request reports the API's own
                // auth error rather than a local one, and the next attempt retries the refresh.
                onFailure = { current },
            )
        }
    }

    private fun isNearExpiry(): Boolean {
        val expiresAt = read(expiryKey)?.toLongOrNull() ?: 0L
        return expiresAt == 0L || System.currentTimeMillis() >= expiresAt - SKEW_MS
    }

    companion object {
        /** Refresh this far ahead of expiry so a long streaming turn cannot outlive its own token. */
        private const val SKEW_MS = 60_000L
    }
}
