@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package me.acardia.amalor.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupedDropdownMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groupSizes: List<Int>,
    modifier: Modifier = Modifier,
    itemContent: @Composable (groupIndex: Int, itemIndex: Int, shapes: MenuItemShapes) -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        val groups = groupSizes.mapIndexedNotNull { index, size -> size.takeIf { it > 0 }?.let { index to it } }
        groups.forEachIndexed { renderedIndex, (groupIndex, itemCount) ->
            if (renderedIndex > 0) Spacer(Modifier.height(2.dp))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(renderedIndex, groups.size)) {
                repeat(itemCount) { itemIndex ->
                    itemContent(groupIndex, itemIndex, MenuDefaults.itemShape(itemIndex, itemCount))
                }
            }
        }
    }
}
