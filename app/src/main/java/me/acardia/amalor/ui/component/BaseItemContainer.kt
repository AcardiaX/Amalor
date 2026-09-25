package me.acardia.amalor.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BaseItemContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val baseShape = LocalSegmentedItemShape.current
    val backgroundColor = MaterialTheme.colorScheme.surfaceBright

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = baseShape,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}
