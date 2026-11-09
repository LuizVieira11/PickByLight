package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.theme.CoralError
import com.uniso.wms.pickbylight.ui.theme.EmeraldSuccess
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.ShelfRackBlue

@Composable
fun NfcSimulatorDialog(
    tagsDisponiveis: List<TagNFC>,
    posicoes: List<Posicao>,
    onDismiss: () -> Unit,
    onTagSelected: ((String) -> Unit)? = null
) {
    val dimensions = LocalWmsDimensions.current
    var customUid by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Simulador de Leitura NFC",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Selecione uma tag cadastrada ou simule uma tag desconhecida:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Opção especial: Tag Não Cadastrada (Cenário 2 da Especificação)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val uid = "04:FF:EE:DD:CC:BB:AA"
                            NfcManager.onTagScanned(uid)
                            onTagSelected?.invoke(uid)
                            onDismiss()
                        },
                    colors = CardDefaults.cardColors(containerColor = CoralError.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(32.dp)
                                .background(CoralError, RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Tag Desconhecida / Não Cadastrada",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = CoralError
                            )
                            Text(
                                text = "UID: 04:FF:EE:DD:CC:BB:AA (Testar Cenário 5.4)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Tags Cadastradas no Armazém:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(tagsDisponiveis) { tag ->
                        val posicao = posicoes.find { it.id == tag.posicaoId }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    NfcManager.onTagScanned(tag.uid)
                                    onTagSelected?.invoke(tag.uid)
                                    onDismiss()
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = if (posicao != null) "${tag.posicaoId} (${posicao.codigo})" else tag.posicaoId,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ShelfRackBlue
                                    )
                                    Text(
                                        text = tag.uid,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(EmeraldSuccess.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Válida",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldSuccess,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Entrada manual de UID
                OutlinedTextField(
                    value = customUid,
                    onValueChange = { customUid = it },
                    label = { Text("Ou digite um UID customizado") },
                    placeholder = { Text("04:A1:2B:3C:4D:5E:99") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            if (customUid.isNotBlank()) {
                TextButton(
                    onClick = {
                        val uid = customUid.trim().uppercase()
                        NfcManager.onTagScanned(uid)
                        onTagSelected?.invoke(uid)
                        onDismiss()
                    }
                ) {
                    Text("Emitir UID Digitado")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}
