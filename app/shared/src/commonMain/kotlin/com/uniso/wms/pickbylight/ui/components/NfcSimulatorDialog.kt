package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.domain.model.Posicao
import com.uniso.wms.pickbylight.domain.model.TagNFC
import com.uniso.wms.pickbylight.nfc.NfcManager
import com.uniso.wms.pickbylight.ui.theme.InkMuted
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions
import com.uniso.wms.pickbylight.ui.theme.OkTeal

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
                text = "Simulador de leitura NFC",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "Selecione uma tag cadastrada ou simule uma tag desconhecida:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkMuted
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Opção especial: Tag não cadastrada (Cenário de erro 5.4)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val uid = "04:FF:EE:DD:CC:BB:AA"
                            NfcManager.onTagScanned(uid)
                            onTagSelected?.invoke(uid)
                            onDismiss()
                        },
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(dimensions.cardCornerRadius)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = dimensions.minTouchTarget)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tag desconhecida",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "UID 04:FF:EE:DD:CC:BB:AA",
                                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                color = InkMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = "Erro",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Tags cadastradas no armazém",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(dimensions.cardCornerRadius)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        itemsIndexed(tagsDisponiveis) { idx, tag ->
                            val posicao = posicoes.find { it.id == tag.posicaoId }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = dimensions.minTouchTarget)
                                    .clickable {
                                        NfcManager.onTagScanned(tag.uid)
                                        onTagSelected?.invoke(tag.uid)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (posicao != null) "${posicao.codigo} (${tag.posicaoId})" else tag.posicaoId,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = tag.uid,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                                        color = InkMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, OkTeal)
                                ) {
                                    Text(
                                        text = "Válida",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OkTeal,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (idx < tagsDisponiveis.lastIndex) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = customUid,
                    onValueChange = { customUid = it },
                    label = { Text("Ou digite um UID customizado") },
                    placeholder = { Text("04:A1:2B:3C:4D:5E:99") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            if (customUid.isNotBlank()) {
                Button(
                    onClick = {
                        val uid = customUid.trim().uppercase()
                        NfcManager.onTagScanned(uid)
                        onTagSelected?.invoke(uid)
                        onDismiss()
                    },
                    modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                    shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Emitir UID")
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = dimensions.minTouchTarget),
                shape = RoundedCornerShape(dimensions.buttonCornerRadius),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text("Fechar", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}
