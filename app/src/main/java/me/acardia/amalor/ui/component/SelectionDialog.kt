package me.acardia.amalor.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** BakaSU SettingsChooseDialog/Frame style (GPL-3.0), using Amalor's shared settings rows. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionDialog(
    title: String,
    values: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    var pending by remember(selected, values) { mutableIntStateOf(selected.coerceIn(values.indices)) }
    val shapes = remember(values.size) {
        List(values.size) { index ->
            val top = if (index == 0) 16.dp else 5.dp
            val bottom = if (index == values.lastIndex) 16.dp else 5.dp
            RoundedCornerShape(top, top, bottom, bottom)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp).padding(horizontal = 32.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 400.dp)
                        .padding(top = 16.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    items(values.size, key = { it }, contentType = { "choice" }) { index ->
                        val isSelected by remember(index, selected, values) { derivedStateOf { pending == index } }
                        BaseWidget(
                            title = values[index],
                            shape = shapes[index],
                            selected = isSelected,
                            onClick = { pending = index },
                            leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    TextButton(onClick = { onSelect(pending); onDismiss() }) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }
    }
}
