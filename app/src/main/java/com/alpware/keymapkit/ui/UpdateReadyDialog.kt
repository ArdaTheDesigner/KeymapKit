package com.alpware.keymapkit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpware.keymapkit.R

@Composable
internal fun UpdateReadyDialog(
    onRestart: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        icon = {
            TonalIcon(
                icon = Icons.Outlined.SystemUpdateAlt,
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_ready_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.update_ready_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(onClick = onRestart) {
                Text(stringResource(R.string.update_restart_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.update_restart_later))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    )
}
