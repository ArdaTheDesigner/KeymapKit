package com.alpware.keymapkit.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun <T> KeymapPredictiveScreenHost(
    current: T,
    previous: T?,
    onBack: () -> Unit,
    depth: (T) -> Int,
    isBackNavigation: Boolean? = null,
    content: @Composable (T) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val animationScope = rememberCoroutineScope()
    val latestPrevious by rememberUpdatedState(previous)
    val latestOnBack by rememberUpdatedState(onBack)
    var predictiveCommitInProgress by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = previous != null) { events ->
        try {
            events.collect { event ->
                progress.snapTo(event.progress.coerceIn(0f, 1f))
            }

            if (latestPrevious != null) {
                predictiveCommitInProgress = true
                latestOnBack()
            }
            progress.snapTo(0f)
        } catch (_: CancellationException) {
            animationScope.launch {
                progress.animateTo(targetValue = 0f, animationSpec = tween(120))
            }
        }
    }

    LaunchedEffect(current) {
        progress.snapTo(0f)
        if (predictiveCommitInProgress) predictiveCommitInProgress = false
    }

    val gestureProgress = progress.value
    Box(Modifier.fillMaxSize()) {
        if (gestureProgress > 0.001f && previous != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = -size.width * 0.06f * (1f - gestureProgress)
                    },
            ) {
                OpaqueScreen { content(previous) }
            }
        }

        AnimatedContent(
            targetState = current,
            transitionSpec = {
                if (predictiveCommitInProgress) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val goingBack = isBackNavigation ?: (depth(targetState) < depth(initialState))
                    val enter = if (goingBack) {
                        slideInHorizontally(animationSpec = tween(240)) { -it / 10 } +
                            fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(animationSpec = tween(260)) { it / 10 } +
                            fadeIn(animationSpec = tween(200))
                    }
                    val exit = if (goingBack) {
                        slideOutHorizontally(animationSpec = tween(240)) { it / 6 } +
                            fadeOut(animationSpec = tween(160))
                    } else {
                        slideOutHorizontally(animationSpec = tween(220)) { -it / 12 } +
                            fadeOut(animationSpec = tween(160))
                    }

                    if (isBackNavigation == null && depth(targetState) == depth(initialState)) {
                        fadeIn(animationSpec = tween(140)) togetherWith
                            fadeOut(animationSpec = tween(100))
                    } else {
                        enter togetherWith exit
                    }
                }
            },
            label = "KeymapScreenTransition",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (gestureProgress > 0f) {
                        translationX = size.width * gestureProgress
                    }
                },
        ) { destination ->
            OpaqueScreen { content(destination) }
        }
    }
}

@Composable
private fun OpaqueScreen(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        content()
    }
}
