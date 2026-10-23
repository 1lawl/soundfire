package app.soundfire.data.soundcloud

import app.soundfire.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * SoundCloud's web app embeds a public client_id in its JS bundle. We scrape it the same way
 * the website bootstraps itself, cache it, and re-scrape when the API starts rejecting it.
 */
class ClientIdProvider(private val http: OkHttpClient, private val prefs: Prefs) {
    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    suspend fun get(): String = cached ?: mutex.withLock {
        cached ?: (prefs.clientId() ?: scrape().also { prefs.setClientId(it) }).also { cached = it }
    }

    /** Called after a 401/403; skips the scrape if another caller already replaced [stale]. */
    suspend fun invalidate(stale: String): String = mutex.withLock {
        cached?.takeIf { it != stale }
            ?: scrape().also {
                cached = it
                prefs.setClientId(it)
            }
    }

    private suspend fun scrape(): String = withContext(Dispatchers.IO) {
        val home = fetch("https://soundcloud.com/")
        val scripts = SCRIPT_URL.findAll(home).map { it.value }.distinct().toList()
        // Fetch every bundle at once; the first one containing the id wins.
        val found = coroutineScope {
            scripts.map { url ->
                async { runCatching { CLIENT_ID.find(fetch(url))?.groupValues?.get(1) }.getOrNull() }
            }.firstNotNullOfOrNull { it.await() }
        }
        found ?: throw IOException("SoundCloud client_id not found in ${scripts.size} scripts")
    }

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body.string()
        }

    private companion object {
        val SCRIPT_URL = Regex("https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js")
        val CLIENT_ID = Regex("client_id[:=]\"([a-zA-Z0-9]{32})\"")
    }
}
