package com.uniso.wms.pickbylight.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uniso.wms.pickbylight.ui.theme.LocalWmsDimensions

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Ocorreu um erro",
    retryLabel: String = "Tentar novamente",
    onRetry: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null
) {
    val dimensions = LocalWmsDimensions.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimensions.screenHorizontalPadding, vertical = dimensions.paddingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (onRetry != null) {
            Spacer(modifier = Modifier.height(dimensions.paddingLarge))
            PrimaryButton(
                text = retryLabel,
                onClick = onRetry,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
        }
    }
}
