package me.acardia.amalor.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

/** Expressive height reveal without scaling the content. */
@Composable
fun ExpandableContent(visible: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec(), expandFrom = Alignment.Top, clip = false),
        exit = shrinkVertically(MaterialTheme.motionScheme.fastEffectsSpec(), shrinkTowards = Alignment.Top, clip = false),
    ) {
        content()
    }
}
