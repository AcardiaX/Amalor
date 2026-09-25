package me.acardia.amalor.ui.component.card

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnimatedFluidBackground(baseColor: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "about_fluid")
    val primary by transition.animateColor(
        baseColor.copy(alpha = 0.9f),
        baseColor.copy(alpha = 0.55f).compositeOver(Color.Magenta.copy(alpha = 0.18f)),
        infiniteRepeatable(tween(4500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "primary",
    )
    val secondary by transition.animateColor(
        baseColor.copy(alpha = 0.65f).compositeOver(Color.Cyan.copy(alpha = 0.22f)),
        baseColor.copy(alpha = 0.8f).compositeOver(Color.Blue.copy(alpha = 0.16f)),
        infiniteRepeatable(tween(3800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "secondary",
    )
    val phase by transition.animateFloat(
        0f, (Math.PI * 2).toFloat(),
        infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "phase",
    )
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = maxOf(size.width, size.height) * 0.85f
            val first = Offset(center.x + size.width * 0.42f * sin(phase), center.y + size.height * 0.35f * cos(phase * 0.8f))
            val second = Offset(center.x + size.width * 0.4f * cos(phase * 0.7f + 2f), center.y + size.height * 0.38f * sin(phase * 0.9f + 1f))
            drawCircle(brush = Brush.radialGradient(listOf(primary, primary.copy(alpha = 0f)), first, radius), center = first, radius = radius)
            drawCircle(brush = Brush.radialGradient(listOf(secondary, secondary.copy(alpha = 0f)), second, radius), center = second, radius = radius)
        }
    }
}
