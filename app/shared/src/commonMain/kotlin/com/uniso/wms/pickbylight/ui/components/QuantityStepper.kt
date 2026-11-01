package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    modifier: Modifier = Modifier
) {
    val dimensions = LocalWmsDimensions.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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

        // Input numérico direto
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { input ->
                val cleaned = input.filter { it.isDigit() }
                if (cleaned.isNotEmpty()) {
                    val intVal = cleaned.toIntOrNull() ?: min
                    onValueChange(intVal.coerceIn(min, max))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.titleMedium.copy(
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier
                .width(54.dp)
                .height(dimensions.minTouchTarget),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
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
