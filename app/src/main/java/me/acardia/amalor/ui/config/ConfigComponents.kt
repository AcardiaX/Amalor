package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.ExpandableContent
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SegmentedColumnScope
import me.acardia.amalor.ui.component.rememberExpandableItemShape

@Composable
internal fun ExpandableConfigHeader(
    title: String,
    description: String,
    icon: Painter,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = rememberExpandableItemShape(expanded, pressed)
    BaseWidget(
        title = title,
        description = description,
        icon = icon,
        shape = shape,
        pressedShape = shape,
        interactionSource = interaction,
        onClick = { onExpandedChange(!expanded) },
        trailingContent = { ExpandCollapseArrow(expanded) },
    )
}

@Composable
internal fun ExpandableConfigSurface(expanded: Boolean, shape: Shape, content: @Composable ColumnScope.() -> Unit) {
    ExpandableContent(visible = expanded) {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceBright) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), content = content)
        }
    }
}

/** Network-type reveal shared with the back-gesture configuration. */
@Composable
internal fun ExpandableConfigSection(visible: Boolean, title: String, content: SegmentedColumnScope.() -> Unit) {
    val headingGap = with(LocalDensity.current) { 16.dp.toPx() }
    ExpandableContent(visible = visible) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.animateEnterExit(
                    enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ).padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
            )
            Box(
                if (visible) Modifier.drawWithContent {
                    clipRect(left = -size.width, top = -headingGap, right = size.width * 2, bottom = size.height * 2) {
                        this@drawWithContent.drawContent()
                    }
                } else Modifier.clipToBounds(),
            ) {
                SegmentedColumn(
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.animateEnterExit(
                        enter = slideInVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it },
                        exit = slideOutVertically(MaterialTheme.motionScheme.fastEffectsSpec()) { -it },
                    ),
                    content = content,
                )
            }
        }
    }
}

/** Highlighted configuration switch shared by network type and native back gesture. */
@Composable
internal fun ConfigMasterSwitch(title: String, checked: Boolean, enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.clip(RoundedCornerShape(35.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { value ->
                haptic.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onCheckedChange(value)
            }).padding(start = 28.dp, end = 20.dp).heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(enabled = enabled, checked = checked, onCheckedChange = null, thumbContent = {
            Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize))
        })
    }
}

@Composable
internal fun ExpandCollapseArrow(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 270f else 90f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "expand_collapse_arrow",
    )
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        stringResource(if (expanded) R.string.collapse_settings else R.string.expand_settings),
        modifier = Modifier.graphicsLayer { rotationZ = rotation },
    )
}

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
internal fun RestartSystemUiAction(enabled: Boolean, titleRes: Int = R.string.restart_system_ui, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Rounded.Refresh, stringResource(titleRes))
    }
}

@Composable
internal fun RestartSystemUiDialog(onDismiss: () -> Unit, onRestart: () -> Boolean,
    titleRes: Int = R.string.restart_system_ui, text: @Composable (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Refresh, null) },
        title = { Text(stringResource(titleRes)) },
        text = text,
        confirmButton = {
            TextButton(onClick = { onDismiss(); onRestart() }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
