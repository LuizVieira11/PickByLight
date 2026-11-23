package com.uniso.wms.pickbylight.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Dashboard WMS")
    object Recebimento : Screen("recebimento", "Recebimento de itens")
    object Separacao : Screen("separacao", "Separação Pick-by-Light")
    object Enderecos : Screen("enderecos", "Gestão de endereços")
    object Catalogo : Screen("catalogo", "Catálogo de itens")
    object Historico : Screen("historico", "Histórico de movimentações")
    object Ajustes : Screen("ajustes", "Ajustes e conectividade")
    object EspList : Screen("esps", "Gerenciamento de ESPs")
    object EspDetail : Screen("esps/{espId}", "Detalhe do ESP") {
        const val ARG_ESP_ID = "espId"
        fun createRoute(espId: String): String = "esps/$espId"
    }
}

