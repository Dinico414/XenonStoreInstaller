package com.xenonware.store

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

@Composable
fun AnimatedGradientBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gradientColors = listOf(
        Color(0xFF356C50) to Color(0xFF1B3829),
        Color(0xFF45A03A) to Color(0xFF1B3829),
        Color(0xFF356C50) to Color(0xFF1B3829),
    )

    var colorIndex by remember { mutableIntStateOf(0) }

    val startColor by animateColorAsState(
        targetValue = gradientColors[colorIndex].first,
        animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
        label = "Start Color"
    )
    val endColor by animateColorAsState(
        targetValue = gradientColors[colorIndex].second,
        animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
        label = "End Color"
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            colorIndex = (colorIndex + 1) % gradientColors.size
        }
    }

    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(startColor, endColor)
            )
        )
    ) {
        content()
    }
}