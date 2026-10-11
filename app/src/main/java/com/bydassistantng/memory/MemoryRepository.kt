package com.bydassistantng.memory

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bydassistantng.data.dataStore
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Memory"

/**
 * Where the assistant's memory lives: on this device only, in the app's private preferences. Two things:
 * the facts it was asked to remember (long-term), and the last few exchanges (short-term). What is in either
 * reaches Google only as part of the assistant's instructions when a conversation is open, like anything else
 * the user says to it.
 */
@Singleton
class MemoryRepository @Inject constructor(@ApplicationContext context: Context) {
    private val dataStore = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private object Keys {
        val FACTS = stringPreferencesKey("memory_facts")
        val RECENT = stringPreferencesKey("memory_recent_exchanges")
    }

    private val factsSerializer = ListSerializer(MemoryFact.serializer())
    private val exchangesSerializer = ListSerializer(Exchange.serializer())

    private fun decodeFacts(raw: String?): List<MemoryFact> =
        raw?.let { runCatching { json.decodeFromString(factsSerializer, it) }.getOrNull() }.orEmpty()

    private fun decodeExchanges(raw: String?): List<Exchange> =
        raw?.let { runCatching { json.decodeFromString(exchangesSerializer, it) }.getOrNull() }.orEmpty()

    /** The facts, oldest first. */
    val facts: Flow<List<MemoryFact>> = dataStore.data.map { decodeFacts(it[Keys.FACTS]) }

    suspend fun currentFacts(): List<MemoryFact> = facts.first()

    suspend fun addFact(text: String): AddOutcome = lock.withLock {
        val current = currentFacts()
        val (updated, outcome) = MemoryFacts.add(current, text, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        if (updated !== current) dataStore.edit { it[Keys.FACTS] = json.encodeToString(factsSerializer, updated) }
        if (outcome is AddOutcome.Added) AppLogger.log(TAG, "Remembered a fact (${updated.size} kept)")
        outcome
    }

    /** Forgets the facts containing [match]; returns how many went. */
    suspend fun forget(match: String): Int = lock.withLock {
        val (updated, removed) = MemoryFacts.forget(currentFacts(), match)
        if (removed > 0) dataStore.edit { it[Keys.FACTS] = json.encodeToString(factsSerializer, updated) }
        removed
    }

    suspend fun deleteFact(id: String) = lock.withLock {
        val updated = currentFacts().filterNot { it.id == id }
        dataStore.edit { it[Keys.FACTS] = json.encodeToString(factsSerializer, updated) }
    }

    suspend fun clearFacts() = lock.withLock {
        dataStore.edit { it.remove(Keys.FACTS) }
        AppLogger.log(TAG, "Long-term memory cleared")
    }

    /** The exchanges still recent enough to be context, oldest first. */
    suspend fun recentExchanges(now: Long = System.currentTimeMillis()): List<Exchange> =
        RecentExchanges.fresh(decodeExchanges(dataStore.data.first()[Keys.RECENT]), now)

    suspend fun recordExchange(user: String, assistant: String, actions: List<String>) = lock.withLock {
        val now = System.currentTimeMillis()
        val current = decodeExchanges(dataStore.data.first()[Keys.RECENT])
        val updated = RecentExchanges.record(RecentExchanges.fresh(current, now), user, assistant, actions, now)
        if (updated != current) dataStore.edit { it[Keys.RECENT] = json.encodeToString(exchangesSerializer, updated) }
    }

    suspend fun clearRecent() = lock.withLock {
        dataStore.edit { it.remove(Keys.RECENT) }
        AppLogger.log(TAG, "Short-term memory cleared")
    }
}
