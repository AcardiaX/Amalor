package me.acardia.amalor.ui.navigation

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private val PagerNavigationSpringSpec: SpringSpec<Float> = spring(
    stiffness = 322.2f,
    dampingRatio = 32.31f / (2f * kotlin.math.sqrt(322.2f)),
    visibilityThreshold = 0.5f,
)

class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(target: Int) {
        if (target == selectedPage) return
        navJob?.cancel()
        selectedPage = target
        isNavigating = true
        navJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(target)
            } finally {
                if (navJob == currentJob) {
                    isNavigating = false
                    if (pagerState.currentPage != target) selectedPage = pagerState.currentPage
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) selectedPage = pagerState.currentPage
    }
}

@Composable
fun rememberMainPagerState(pagerState: PagerState): MainPagerState {
    val coroutineScope = rememberCoroutineScope()
    return remember(pagerState, coroutineScope) {
        MainPagerState(pagerState, coroutineScope)
    }
}

private suspend fun PagerState.springAnimateToPage(target: Int) {
    if (target !in 0 until pageCount) return
    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
    if (pageSize <= 0) return
    val distance = (target - currentPage - currentPageOffsetFraction) * pageSize.toFloat()
    var previousValue = 0f
    scroll {
        updateTargetPage(target)
        animate(
            initialValue = 0f,
            targetValue = distance,
            animationSpec = PagerNavigationSpringSpec,
        ) { value, _ ->
            previousValue += scrollBy(value - previousValue)
        }
    }
}
