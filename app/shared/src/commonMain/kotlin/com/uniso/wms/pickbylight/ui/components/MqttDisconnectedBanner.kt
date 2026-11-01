package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.ui.theme.CoralError

/**
 * Banner persistente de aviso quando o broker MQTT está offline (Seção 5.1).
 * - Exibe "⚠️ Broker MQTT desconectado. Tentando reconectar..." durante o loop de backoff.
 * - Exibe "Broker MQTT desconectado. Acesse os Ajustes para tentar novamente." após esgotar as tentativas (ERROR).
 * - Desaparece automaticamente quando a conexão é restabelecida (CONNECTED).
 */
@Composable
fun MqttDisconnectedBanner(
    connectionState: MqttConnectionState,
    modifier: Modifier = Modifier
) {
    if (connectionState != MqttConnectionState.CONNECTED) {
        val bannerText = when (connectionState) {
            MqttConnectionState.ERROR -> "Broker MQTT desconectado. Acesse os Ajustes para tentar novamente."
            MqttConnectionState.CONNECTING, MqttConnectionState.DISCONNECTED -> "⚠️ Broker MQTT desconectado. Tentando reconectar..."
            MqttConnectionState.CONNECTED -> ""
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(CoralError)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = bannerText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
