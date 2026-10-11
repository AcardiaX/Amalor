package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R

@Composable
internal fun ConfigBackButton(onClick: () -> Unit) {
    Row {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
internal fun RestartSystemUiAction(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Rounded.Refresh, stringResource(R.string.restart_system_ui))
    }
}

@Composable
internal fun RestartSystemUiDialog(onDismiss: () -> Unit, onRestart: () -> Boolean) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Refresh, null) },
        title = { Text(stringResource(R.string.restart_system_ui)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onRestart() }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
