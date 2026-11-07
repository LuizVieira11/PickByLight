package com.uniso.wms.pickbylight.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object BrokerUrlDataStore {
    private val savedUrlState = MutableStateFlow<String?>(null)
    private val sharedFlow: Flow<String?> = savedUrlState.asStateFlow()

    actual fun getBrokerUrl(): Flow<String?> = sharedFlow

    actual val savedBrokerUrlFlow: Flow<String?>
        get() = sharedFlow

    actual suspend fun saveBrokerUrl(url: String) {
        val clean = url.trim()
        savedUrlState.value = clean.ifBlank { null }
    }

    actual suspend fun getSavedBrokerUrl(): String? = savedUrlState.value
}
