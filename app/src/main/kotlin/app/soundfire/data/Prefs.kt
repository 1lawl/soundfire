package app.soundfire.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "soundfire")

class Prefs(context: Context) {
    private val store = context.applicationContext.dataStore

    val username: Flow<String?> = store.data.map { it[USERNAME] }

    suspend fun clientId(): String? = store.data.first()[CLIENT_ID]
    suspend fun setClientId(id: String) = store.edit { it[CLIENT_ID] = id }

    suspend fun setUsername(name: String) = store.edit { it[USERNAME] = name }

    suspend fun lastCandidateRefresh(): Long = store.data.first()[LAST_REFRESH] ?: 0L
    suspend fun setLastCandidateRefresh(at: Long) = store.edit { it[LAST_REFRESH] = at }

    private companion object {
        val CLIENT_ID = stringPreferencesKey("client_id")
        val USERNAME = stringPreferencesKey("username")
        val LAST_REFRESH = longPreferencesKey("last_candidate_refresh")
    }
}
