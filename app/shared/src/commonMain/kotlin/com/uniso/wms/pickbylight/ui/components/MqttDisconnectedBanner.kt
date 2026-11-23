package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.data.mqtt.MqttConnectionState
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.WarnAmber
import org.jetbrains.compose.resources.painterResource
import pickbylight.app.shared.generated.resources.Res
import pickbylight.app.shared.generated.resources.ic_cloud_off

/**
 * Banner persistente de aviso quando o broker MQTT está offline (Seção 5.1).
 * - Exibe "Broker MQTT desconectado. Tentando reconectar..." durante o loop de backoff.
 * - Exibe "Broker MQTT desconectado. Acesse os Ajustes para tentar novamente." após esgotar as tentativas (ERROR).
 * - Desaparece automaticamente quando a conexão é restabelecida (CONNECTED).
 */
@Composable
fun MqttDisconnectedBanner(
    connectionState: MqttConnectionState,
    modifier: Modifier = Modifier
) {
    if (connectionState != MqttConnectionState.CONNECTED) {
        val dimensions = LocalWmsDimensions.current
        val bannerText = when (connectionState) {
            MqttConnectionState.ERROR -> "Sem conexão com o broker. Abra Ajustes para reconectar."
            MqttConnectionState.CONNECTING, MqttConnectionState.DISCONNECTED -> "Sem conexão com o broker. Reconectando…"
            MqttConnectionState.CONNECTED -> ""
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensions.screenHorizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_cloud_off),
                    contentDescription = null, // Decorativo: o texto ao lado informa o estado offline do broker
                    tint = WarnAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = bannerText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = WarnAmber
                )
            }
            HorizontalDivider(thickness = 1.dp, color = WarnAmber)
        }
    }
}
