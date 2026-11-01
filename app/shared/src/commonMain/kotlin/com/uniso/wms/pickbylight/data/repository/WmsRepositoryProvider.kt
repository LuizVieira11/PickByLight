package com.uniso.wms.pickbylight.data.repository

import com.uniso.wms.pickbylight.domain.repository.WmsRepository

expect object WmsRepositoryProvider {
    fun getRepository(): WmsRepository
}
