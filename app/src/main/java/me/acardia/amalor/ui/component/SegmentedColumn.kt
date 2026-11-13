package me.acardia.amalor.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt

private val CornerRadius = 16.dp
private val ConnectionRadius = 5.dp
val LocalSegmentedItemShape = compositionLocalOf<Shape> { RoundedCornerShape(0.dp) }

@Composable
fun rememberExpandableItemShape(expanded: Boolean, pressed: Boolean): Shape {
    val bottomRadius = animateDpAsState(
        targetValue = if (pressed || !expanded) CornerRadius else 4.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "expandable_item_bottom_radius",
    )
    return remember {
        object : Shape {
            override fun createOutline(
                size: androidx.compose.ui.geometry.Size,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density,
            ) = RoundedCornerShape(CornerRadius, CornerRadius, bottomRadius.value, bottomRadius.value)
                .createOutline(size, layoutDirection, density)
        }
    }
}

@Immutable
internal data class SegmentedItem(
    val key: Any?,
    val visible: Boolean,
    val customTopPadding: Dp? = null,
    val forceFlatTop: Boolean = false,
    val forceFlatBottom: Boolean = false,
    val content: @Composable (Shape) -> Unit,
)

class SegmentedColumnScope internal constructor() {
    internal val items = mutableListOf<SegmentedItem>()

    fun item(
        key: Any? = null,
        animatedVisibility: Boolean = true,
        topPadding: Dp? = null,
        forceFlatTop: Boolean = false,
        forceFlatBottom: Boolean = false,
        content: @Composable (Shape) -> Unit,
    ) {
        items += SegmentedItem(
            key = key ?: items.size,
            visible = animatedVisibility,
            customTopPadding = topPadding,
            forceFlatTop = forceFlatTop,
            forceFlatBottom = forceFlatBottom,
            content = content,
        )
    }
}

@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: SegmentedColumnScope.() -> Unit,
) {
    val items = SegmentedColumnScope().apply(content).items
    if (items.isEmpty()) return

    Column(modifier.padding(contentPadding)) {
        if (title.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
            )
        }

        val firstVisible = items.indexOfFirst { it.visible }
        val lastVisible = items.indexOfLast { it.visible }
        items.forEachIndexed { index, item ->
            key(item.key ?: index) {
                val topRadius = if (item.forceFlatTop) 0.dp else if (index == firstVisible) CornerRadius else ConnectionRadius
                val bottomRadius = if (item.forceFlatBottom) 0.dp else if (index == lastVisible) CornerRadius else ConnectionRadius
                val shape = remember(topRadius, bottomRadius) {
                    RoundedCornerShape(topRadius, topRadius, bottomRadius, bottomRadius)
                }
                val progress = animateFloatAsState(
                    if (item.visible) 1f else 0f,
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "segmented_visibility",
                )
                val topPadding = animateDpAsState(
                    item.customTopPadding ?: if (index == firstVisible) 0.dp else ListItemDefaults.SegmentedGap,
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "segmented_top_padding",
                )
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val gap = (topPadding.value.toPx() * progress.value.coerceIn(0f, 1f))
                                .roundToInt().coerceIn(0, constraints.maxHeight)
                            val child = measurable.measure(constraints.offset(vertical = -gap))
                            layout(child.width, child.height + gap) { child.placeRelative(0, gap) }
                        }
                        .graphicsLayer {
                            alpha = progress.value
                        },
                ) {
                    CompositionLocalProvider(LocalSegmentedItemShape provides shape) {
                        item.content(shape)
                    }
                }
            }
        }
    }
}

@Composable
fun BaseWidget(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    shape: Shape,
    icon: Painter? = null,
    iconVector: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onTrailingClick: (() -> Unit)? = null,
    trailingDivider: Boolean = false,
    foreContent: @Composable () -> Unit = {},
    enabled: Boolean = true,
    trailingContent: @Composable ((MutableInteractionSource) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    pressedShape: Shape = RoundedCornerShape(CornerRadius),
    centerContent: Boolean = false,
    selected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val internalPadding = (4 * LocalDensity.current.fontScale).dp
    val haptic = LocalHapticFeedback.current
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val trailingContentInteractionSource = if (onTrailingClick != null || trailingDivider) {
        remember { MutableInteractionSource() }
    } else {
        resolvedInteractionSource
    }
    val pressed = if (onClick != null) resolvedInteractionSource.collectIsPressedAsState().value else false
    val animatedShape = rememberAnimatedShape(
        if (enabled && pressed) pressedShape else shape,
        MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val alpha = if (enabled) 1f else 0.38f
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright
    val baseContentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val leadingColor = if (selected) baseContentColor else MaterialTheme.colorScheme.onSurfaceVariant
    val descriptionColor = baseContentColor.copy(alpha = 0.7f)
    val headline: @Composable () -> Unit = {
        Box(
            Modifier.padding(
                top = internalPadding,
                bottom = if (description == null) internalPadding else 0.dp,
            ).alpha(alpha),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (enabled) foreContent()
        }
    }
    val colors = ListItemDefaults.colors(
        containerColor = containerColor,
        contentColor = baseContentColor,
        leadingContentColor = leadingColor,
        supportingContentColor = descriptionColor,
        disabledContainerColor = containerColor,
        disabledContentColor = baseContentColor,
        disabledLeadingContentColor = leadingColor,
        disabledSupportingContentColor = descriptionColor,
    )
    val shapes = ListItemDefaults.shapes(
        shape = animatedShape,
        pressedShape = animatedShape,
        selectedShape = animatedShape,
        focusedShape = animatedShape,
        hoveredShape = animatedShape,
        draggedShape = animatedShape,
    )
    if (onClick != null) {
        ListItem(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            enabled = enabled,
            content = headline,
            verticalAlignment = if (centerContent) Alignment.CenterVertically else ListItemDefaults.verticalAlignment(),
            supportingContent = description?.let { text ->
                {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = descriptionColor,
                        modifier = Modifier.padding(bottom = internalPadding).alpha(alpha),
                    )
                }
            },
            colors = colors,
            shapes = shapes,
            interactionSource = resolvedInteractionSource,
            leadingContent = leadingContent ?: if (icon != null || iconVector != null) {
                {
                when {
                    icon != null -> androidx.compose.material3.Icon(icon, null, modifier = Modifier.alpha(alpha))
                    iconVector != null -> androidx.compose.material3.Icon(iconVector, null, modifier = Modifier.alpha(alpha))
                }
                }
            } else null,
            trailingContent = trailingContent?.let { content ->
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trailingDivider) VerticalDivider(modifier = Modifier.height(32.dp))
                        Box(
                            modifier = Modifier.then(
                                if (onTrailingClick != null) {
                                    Modifier.clickable(
                                        interactionSource = trailingContentInteractionSource,
                                        indication = LocalIndication.current,
                                        onClick = onTrailingClick,
                                    )
                                } else Modifier,
                            ).padding(start = if (trailingDivider) 16.dp else 0.dp),
                        ) { content(trailingContentInteractionSource) }
                    }
                }
            },
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        ListItem(
            content = headline,
            supportingContent = description?.let { text ->
                {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = descriptionColor,
                        modifier = Modifier.padding(bottom = internalPadding).alpha(alpha),
                    )
                }
            },
            colors = colors,
            leadingContent = leadingContent ?: if (icon != null || iconVector != null) {
                {
                when {
                    icon != null -> androidx.compose.material3.Icon(icon, null, modifier = Modifier.alpha(alpha))
                    iconVector != null -> androidx.compose.material3.Icon(iconVector, null, modifier = Modifier.alpha(alpha))
                }
                }
            } else null,
            trailingContent = trailingContent?.let { content -> { content(trailingContentInteractionSource) } },
            modifier = modifier
                .fillMaxWidth()
                .clip(animatedShape)
                .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
        )
    }
}

@Composable
fun SwitchWidget(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    shape: Shape,
    icon: Painter? = null,
    iconVector: ImageVector? = null,
    enabled: Boolean = true,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    trailingDivider: Boolean = false,
    centerContent: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val handleCheckedChange: (Boolean) -> Unit = { value ->
        haptic.performHapticFeedback(
            if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        )
        onCheckedChange(value)
    }
    BaseWidget(
        modifier = modifier,
        title = title,
        description = description,
        shape = shape,
        icon = icon,
        iconVector = iconVector,
        enabled = enabled,
        onClick = onClick ?: { handleCheckedChange(!checked) },
        trailingDivider = trailingDivider,
        centerContent = centerContent,
        trailingContent = { interactionSource ->
            Switch(
                enabled = enabled,
                checked = checked,
                onCheckedChange = handleCheckedChange,
                interactionSource = interactionSource,
                colors = SwitchDefaults.colors(
                    checkedIconColor = MaterialTheme.colorScheme.primary,
                    uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                thumbContent = {
                    androidx.compose.material3.Icon(
                        if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                        null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
    )
}
