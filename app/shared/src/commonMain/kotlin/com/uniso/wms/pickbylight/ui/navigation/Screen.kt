package com.uniso.wms.pickbylight.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Dashboard WMS")
    object Recebimento : Screen("recebimento", "Recebimento de Itens")
    object Separacao : Screen("separacao", "Separação Pick-by-Light")
    object Enderecos : Screen("enderecos", "Gestão de Endereços")
    object Catalogo : Screen("catalogo", "Catálogo de Itens")
    object Historico : Screen("historico", "Histórico de Movimentações")
    object Ajustes : Screen("ajustes", "Ajustes e Conectividade")
}
