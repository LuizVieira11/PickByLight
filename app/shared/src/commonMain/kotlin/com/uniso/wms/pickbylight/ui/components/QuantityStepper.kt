package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions

@Composable
fun QuantityStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int = 1,
    max: Int = 9999,
    expandField: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Botão Decrescer (-)
        Box(
            modifier = Modifier
                .size(dimensions.minTouchTarget)
                .background(
                    color = if (value > min) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { if (value > min) onValueChange(value - 1) },
                enabled = value > min,
                modifier = Modifier.size(dimensions.minTouchTarget)
            ) {
                Text(
                    text = "−",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (value > min) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
                )
            }
        }

        // Input numérico direto com largura mínima ampla e sem sobreposição
        val fieldModifier = if (expandField) {
            Modifier
                .weight(1f)
                .widthIn(min = 88.dp)
                .height(dimensions.minTouchTarget)
        } else {
            Modifier
                .widthIn(min = 88.dp)
                .defaultMinSize(minWidth = 88.dp)
                .height(dimensions.minTouchTarget)
        }

        BasicTextField(
            value = value.toString(),
            onValueChange = { input ->
                val cleaned = input.filter { it.isDigit() }
                if (cleaned.isEmpty()) {
                    onValueChange(min)
                } else {
                    val intVal = cleaned.toIntOrNull() ?: min
                    onValueChange(intVal.coerceIn(min, max))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = fieldModifier,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    innerTextField()
                }
            }
        )

        // Botão Acrescer (+)
        Box(
            modifier = Modifier
                .size(dimensions.minTouchTarget)
                .background(
                    color = if (value < max) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { if (value < max) onValueChange(value + 1) },
                enabled = value < max,
                modifier = Modifier.size(dimensions.minTouchTarget)
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (value < max) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
