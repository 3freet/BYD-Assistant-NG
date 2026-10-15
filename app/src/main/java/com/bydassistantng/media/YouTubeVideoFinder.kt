package com.bydassistantng.media

import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "YouTubeVideoFinder"

/**
 * Finds the video a spoken phrase is about, for the places where nobody can press the first result (the
 * instrument cluster has no touch): the page of search results is fetched the way a browser does, and the first
 * video on it is taken. No account, no API key. It is the same phrase and the same results the app's own search
 * link ([YouTubeLibrary.searchUrl]) shows, so what plays is what the main screen would have shown first.
 */
object YouTubeVideoFinder {
    private const val TIMEOUT_SECONDS = 8L

    // The desktop page lists its results as plain JSON; the consent cookie keeps a consent page from coming first.
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** The first video for [query], or null when none was found or the page could not be fetched. */
    suspend fun find(query: String): YouTubeLibrary.VideoHit? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(YouTubeLibrary.searchUrl(query))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.8")
                .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.log(TAG, "Search for \"$query\" answered HTTP ${response.code}")
                    return@use null
                }
                YouTubeLibrary.firstVideo(response.body?.string().orEmpty()).also {
                    AppLogger.log(TAG, "Search for \"$query\" -> ${it?.let { hit -> "${hit.id} \"${hit.title}\"" } ?: "no video"}")
                }
            }
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Could not search YouTube for \"$query\"", e)
            null
        }
    }
}
