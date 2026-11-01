package com.uniso.wms.pickbylight.data.repository

import com.uniso.wms.pickbylight.domain.repository.WmsRepository

actual object WmsRepositoryProvider {
    private val inMemoryInstance: WmsRepository by lazy { InMemoryWmsRepository() }

    actual fun getRepository(): WmsRepository = inMemoryInstance
}
