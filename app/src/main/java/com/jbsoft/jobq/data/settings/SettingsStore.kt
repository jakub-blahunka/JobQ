package com.jbsoft.jobq.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jbsoft.jobq.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) : SettingsRepository {

	override val lastRequest: Flow<String> = context.dataStore.data.map { it[LAST_REQUEST].orEmpty() }

	override suspend fun setLastRequest(request: String) {
		context.dataStore.edit { it[LAST_REQUEST] = request }
	}

	val activeModelId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_MODEL] }

	suspend fun setActiveModelId(id: String?) {
		context.dataStore.edit { if (id == null) it.remove(ACTIVE_MODEL) else it[ACTIVE_MODEL] = id }
	}

	private companion object {
		val LAST_REQUEST = stringPreferencesKey("last_request")
		val ACTIVE_MODEL = stringPreferencesKey("active_model")
	}
}
