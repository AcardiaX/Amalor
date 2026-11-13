/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.acardia.amalor.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Adapted from BakaSU settings/material3internal/AnimatedShape.kt (AOSP).
 * Corner values are observed by outline rendering, never by the row's composition.
 */
private class AnimatedShapeState(initial: RoundedCornerShape, val spec: FiniteAnimationSpec<Float>) {
    private var target = initial
    var size = Size.Zero
    var density: Density = Density(1f)
    private val corners = arrayOfNulls<Animatable<Float, AnimationVector1D>>(4)

    private fun corner(shape: RoundedCornerShape, index: Int) = when (index) {
        0 -> shape.topStart
        1 -> shape.topEnd
        2 -> shape.bottomStart
        else -> shape.bottomEnd
    }

    fun value(index: Int): Float = (corners[index] ?: Animatable(corner(target, index).toPx(size, density))
        .also { corners[index] = it }).value.coerceIn(0f, minOf(size.width, size.height) / 2f)

    suspend fun animateTo(target: RoundedCornerShape) = coroutineScope {
        // A settings update can arrive before the first outline initializes its corner animatables.
        this@AnimatedShapeState.target = target
        for (index in corners.indices) launch {
            corners[index]?.animateTo(corner(target, index).toPx(size, density), spec)
        }
    }
}

@Composable
internal fun rememberAnimatedShape(target: Shape, spec: FiniteAnimationSpec<Float>): Shape {
    // Expandable headers already supply an outline-observed shape of their own.
    if (target !is RoundedCornerShape) return target
    val density = LocalDensity.current
    val state = remember(spec, density) { AnimatedShapeState(target, spec) }
    state.density = density
    val targets = remember(state) { Channel<RoundedCornerShape>(Channel.CONFLATED) }
    SideEffect { targets.trySend(target) }
    LaunchedEffect(state, targets) {
        for (next in targets) {
            val latest = targets.tryReceive().getOrNull() ?: next
            launch { state.animateTo(latest) }
        }
    }
    return remember(state) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                state.size = size
                return RoundedCornerShape(state.value(0), state.value(1), state.value(3), state.value(2))
                    .createOutline(size, layoutDirection, density)
            }
        }
    }
}
