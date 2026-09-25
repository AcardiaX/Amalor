package me.acardia.amalor.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val CornerRadius = 16.dp
private val ConnectionRadius = 5.dp
private val SpringSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 800f)
private val DpSpringSpec = spring<Dp>(dampingRatio = 0.5f, stiffness = 800f)

val LocalSegmentedItemShape = compositionLocalOf<Shape> { RoundedCornerShape(0.dp) }

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
                val topRadius by animateDpAsState(
                    if (item.forceFlatTop) 0.dp else if (index == firstVisible) CornerRadius else ConnectionRadius,
                    DpSpringSpec,
                    label = "segmented_top_radius",
                )
                val bottomRadius by animateDpAsState(
                    if (item.forceFlatBottom) 0.dp else if (index == lastVisible) CornerRadius else ConnectionRadius,
                    DpSpringSpec,
                    label = "segmented_bottom_radius",
                )
                val progress by animateFloatAsState(
                    if (item.visible) 1f else 0f,
                    SpringSpec,
                    label = "segmented_visibility",
                )
                val shape = RoundedCornerShape(topRadius, topRadius, bottomRadius, bottomRadius)
                val topPadding by animateDpAsState(
                    item.customTopPadding ?: if (index == firstVisible) 0.dp else ListItemDefaults.SegmentedGap,
                    DpSpringSpec,
                    label = "segmented_top_padding",
                )
                Box(
                    Modifier
                        .padding(top = topPadding)
                        .graphicsLayer {
                            alpha = progress
                            scaleY = progress
                            transformOrigin = TransformOrigin(0.5f, 0f)
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
    foreContent: @Composable () -> Unit = {},
    enabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val internalPadding = (4 * LocalDensity.current.fontScale).dp
    val haptic = LocalHapticFeedback.current
    val alpha = if (enabled) 1f else 0.38f
    val baseContentColor = MaterialTheme.colorScheme.onSurface
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
        containerColor = MaterialTheme.colorScheme.surfaceBright,
        contentColor = baseContentColor,
        leadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        supportingContentColor = descriptionColor,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
        disabledContentColor = baseContentColor,
        disabledLeadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledSupportingContentColor = descriptionColor,
    )
    val shapes = ListItemDefaults.shapes(
        shape = shape,
        pressedShape = RoundedCornerShape(CornerRadius),
        selectedShape = shape,
        focusedShape = shape,
        hoveredShape = shape,
    )
    if (onClick != null) {
        ListItem(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            enabled = enabled,
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
            shapes = shapes,
            leadingContent = if (icon != null || iconVector != null) {
                {
                when {
                    icon != null -> androidx.compose.material3.Icon(icon, null, modifier = Modifier.alpha(alpha))
                    iconVector != null -> androidx.compose.material3.Icon(iconVector, null, modifier = Modifier.alpha(alpha))
                }
                }
            } else null,
            trailingContent = trailingContent,
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
            leadingContent = if (icon != null || iconVector != null) {
                {
                when {
                    icon != null -> androidx.compose.material3.Icon(icon, null, modifier = Modifier.alpha(alpha))
                    iconVector != null -> androidx.compose.material3.Icon(iconVector, null, modifier = Modifier.alpha(alpha))
                }
                }
            } else null,
            trailingContent = trailingContent,
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
        )
    }
}
