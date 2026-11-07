package com.uniso.wms.pickbylight.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

actual object BrokerUrlDataStore {
    private val KEY_BROKER_URL = stringPreferencesKey("mqtt_broker_url")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dataStoreInstance: DataStore<Preferences>? = null
    private val dataStoreHolder = MutableStateFlow<DataStore<Preferences>?>(null)

    // Espelho reativo sincronizado com o DataStore (e fallback para testes unitários sem Context Android)
    private val memoryMirror = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sharedBrokerUrlFlow: Flow<String?> = dataStoreHolder
        .flatMapLatest { ds ->
            if (ds != null) {
                ds.data.map { prefs ->
                    val fromDisk = prefs[KEY_BROKER_URL]?.takeIf { it.isNotBlank() }
                    if (fromDisk != null) {
                        memoryMirror.value = fromDisk
                    }
                    fromDisk ?: memoryMirror.value
                }
            } else {
                memoryMirror
            }
        }
        .distinctUntilChanged()

    fun init(context: Context) {
        val ds = dataStoreInstance ?: synchronized(this) {
            dataStoreInstance ?: PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(context.applicationContext.filesDir, "datastore/wms_mqtt_settings.preferences_pb") }
            ).also { created ->
                dataStoreInstance = created
                dataStoreHolder.value = created
                scope.launch {
                    created.data.map { prefs -> prefs[KEY_BROKER_URL] }.collect { saved ->
                        if (!saved.isNullOrBlank()) {
                            memoryMirror.value = saved
                        }
                    }
                }
            }
        }
        dataStoreHolder.value = ds
        scope.launch {
            val initial = ds.data.map { it[KEY_BROKER_URL] }.first()
            if (!initial.isNullOrBlank()) {
                memoryMirror.value = initial
            }
        }
    }

    actual fun getBrokerUrl(): Flow<String?> = sharedBrokerUrlFlow

    actual val savedBrokerUrlFlow: Flow<String?>
        get() = sharedBrokerUrlFlow

    actual suspend fun saveBrokerUrl(url: String) {
        val clean = url.trim()
        memoryMirror.value = clean.ifBlank { null }
        dataStoreInstance?.edit { prefs ->
            if (clean.isBlank()) {
                prefs.remove(KEY_BROKER_URL)
            } else {
                prefs[KEY_BROKER_URL] = clean
            }
        }
    }

    actual suspend fun getSavedBrokerUrl(): String? {
        val ds = dataStoreInstance
        if (ds != null) {
            val fromDisk = ds.data.map { it[KEY_BROKER_URL] }.first()
            if (!fromDisk.isNullOrBlank()) {
                memoryMirror.value = fromDisk
                return fromDisk
            }
        }
        return memoryMirror.value
    }
}
