package com.uniso.wms.pickbylight.data.repository

import com.uniso.wms.pickbylight.domain.repository.WmsRepository

actual object WmsRepositoryProvider {
    @Volatile
    private var repositoryInstance: WmsRepository? = null

    fun setRepository(repository: WmsRepository) {
        repositoryInstance = repository
    }

    actual fun getRepository(): WmsRepository {
        return repositoryInstance ?: synchronized(this) {
            repositoryInstance ?: InMemoryWmsRepository().also { repositoryInstance = it }
        }
    }
}
