package com.uniso.wms.pickbylight.ui.components

internal fun formatUnidade(unidade: String?): String =
    if (unidade.isNullOrBlank() || unidade.equals("UN", ignoreCase = true)) "un" else unidade
