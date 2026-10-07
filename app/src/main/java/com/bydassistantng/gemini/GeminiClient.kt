package com.bydassistantng.gemini

import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiClient"
private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

// 429 (rate limited) and 503 (model overloaded) are the two statuses Gemini itself documents as
// transient/backoff-and-retry — see the "UNAVAILABLE... usually temporary" message a 503 carries.
// Anything else (4xx client errors, malformed requests, auth) won't fix itself on retry.
private val RETRYABLE_HTTP_CODES = setOf(429, 503)
private const val MAX_RETRIES = 2
private const val RETRY_BASE_DELAY_MS = 1_000L

enum class GeminiError {
    INVALID_API_KEY,
    // Distinct from INVALID_API_KEY on purpose: a 403 "Your project has been denied access" means
    // Google blocked the whole Cloud project behind the key. Telling the user to re-enter the key
    // (what this used to say) sends them in circles — a different key from the same project fails
    // identically; they need a key from a healthy project or to contact Google.
    PERMISSION_DENIED,
    RATE_LIMITED,
    NETWORK_ERROR,
    PROVIDER_ERROR,
    EMPTY_RESPONSE,
}

sealed interface GeminiResult {
    data class Success(val content: GeminiContent) : GeminiResult
    data class Failure(val error: GeminiError, val detail: String? = null) : GeminiResult {
        /** The human-readable `error.message` from Google's JSON error body, if it parses. */
        val serverMessage: String?
            get() = detail?.let {
                runCatching {
                    Json.parseToJsonElement(it).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
            }
    }
}

/**
 * Plain-OkHttp client for the classic `v1beta/models/{model}:generateContent` endpoint —
 * deliberately no Retrofit, to avoid depending on a kotlinx.serialization Retrofit converter whose
 * current version this project couldn't confidently pin. Callers own turn/history assembly
 * ([ConversationController]); this class only knows how to make one request and parse one response.
 *
 * Every call is logged to [AppLogger] with timing and, on failure, the full HTTP status + response
 * body — this is what a "Gemini returned an error" report needs to actually diagnose, since neither
 * logcat (doesn't survive this head unit's restarts) nor the bare [GeminiError] enum shown in the UI
 * carries enough detail on its own.
 */
@Singleton
class GeminiClient @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateContent(apiKey: String, model: String, request: GeminiRequest): GeminiResult {
        var attempt = 0
        while (true) {
            val (result, httpCode) = attemptGenerateContent(apiKey, model, request)
            val shouldRetry = httpCode in RETRYABLE_HTTP_CODES && attempt < MAX_RETRIES
            if (!shouldRetry) return result

            attempt++
            val delayMs = RETRY_BASE_DELAY_MS * attempt
            AppLogger.log(TAG, "Retrying after HTTP $httpCode (attempt $attempt/$MAX_RETRIES, waiting ${delayMs}ms)")
            delay(delayMs)
        }
    }

    /** @return the result plus the raw HTTP status code (0 if the request never got a response at
     * all, e.g. a network error) so the retry loop can decide without re-deriving it from [GeminiError]. */
    private suspend fun attemptGenerateContent(apiKey: String, model: String, request: GeminiRequest): Pair<GeminiResult, Int> =
        withContext(Dispatchers.IO) {
            val bodyJson = json.encodeToString(GeminiRequest.serializer(), request)
            val httpRequest = Request.Builder()
                .url("$BASE_URL/$model:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val toolCount = request.tools?.sumOf { it.functionDeclarations?.size ?: 0 } ?: 0
            val startMs = SystemClock.elapsedRealtime()
            AppLogger.log(TAG, "-> POST $model:generateContent (${bodyJson.length} chars request, $toolCount tools, ${request.contents.size} history parts)")

            try {
                httpClient.newCall(httpRequest).execute().use { response ->
                    val elapsedMs = SystemClock.elapsedRealtime() - startMs
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful) {
                        AppLogger.logError(TAG, "<- HTTP ${response.code} after ${elapsedMs}ms — $responseBody")
                        return@withContext GeminiResult.Failure(errorForHttpCode(response.code, responseBody), responseBody) to response.code
                    }
                    if (responseBody.isNullOrBlank()) {
                        AppLogger.logError(TAG, "<- HTTP ${response.code} after ${elapsedMs}ms but response body was empty")
                        return@withContext GeminiResult.Failure(GeminiError.EMPTY_RESPONSE) to response.code
                    }

                    AppLogger.log(TAG, "<- HTTP ${response.code} after ${elapsedMs}ms (${responseBody.length} chars response)")
                    val parsed = json.decodeFromString(GeminiResponse.serializer(), responseBody)
                    val content = parsed.candidates?.firstOrNull()?.content
                        ?: run {
                            AppLogger.logError(TAG, "Response parsed OK but had no candidates/content — $responseBody")
                            return@withContext GeminiResult.Failure(GeminiError.EMPTY_RESPONSE, responseBody) to response.code
                        }
                    GeminiResult.Success(content) to response.code
                }
            } catch (e: IOException) {
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                AppLogger.logError(TAG, "Network error after ${elapsedMs}ms", e)
                GeminiResult.Failure(GeminiError.NETWORK_ERROR, e.message) to 0
            } catch (e: Exception) {
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                AppLogger.logError(TAG, "Request/response parsing error after ${elapsedMs}ms", e)
                GeminiResult.Failure(GeminiError.PROVIDER_ERROR, e.message) to 0
            }
        }

    private fun errorForHttpCode(code: Int, body: String?): GeminiError = when {
        code == 401 -> GeminiError.INVALID_API_KEY
        code == 403 -> GeminiError.PERMISSION_DENIED
        // Google reports a malformed/unknown key as HTTP 400 INVALID_ARGUMENT with reason
        // API_KEY_INVALID, not as 401 — without this a truly bad key looked like a generic error.
        code == 400 && body?.contains("API_KEY_INVALID") == true -> GeminiError.INVALID_API_KEY
        code == 429 -> GeminiError.RATE_LIMITED
        else -> GeminiError.PROVIDER_ERROR
    }
}
