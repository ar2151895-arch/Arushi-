package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AssistantStatus
import com.example.ui.theme.ArushiCyan
import com.example.ui.theme.ArushiSaffron
import com.example.ui.theme.ArushiViolet
import com.example.ui.theme.ArushiVioletLight
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArushiOrbVisualizer(
    status: AssistantStatus,
    amplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_animation")

    // Idle / breathing pulse
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_pulse"
    )

    // Rotation for thinking swirl
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "swirl_rotation"
    )

    // Wave ripple phase
    val ripplePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple_phase"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(170.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 90.dp),
                onClick = onClick
            )
            .testTag("arushi_orb_visualizer")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = size.minDimension * 0.28f

            val effectiveScale = when (status) {
                AssistantStatus.LISTENING -> 1f + (amplitude * 0.45f)
                AssistantStatus.SPEAKING -> 1f + (amplitude * 0.55f)
                AssistantStatus.THINKING, AssistantStatus.EXECUTING_ACTION -> 1.05f
                else -> idlePulse
            }

            val dynamicRadius = baseRadius * effectiveScale

            // Draw outer ambient glow
            val glowColor = when (status) {
                AssistantStatus.LISTENING -> ArushiCyan
                AssistantStatus.SPEAKING -> ArushiSaffron
                AssistantStatus.THINKING -> ArushiVioletLight
                AssistantStatus.EXECUTING_ACTION -> ArushiCyan
                AssistantStatus.ERROR -> Color(0xFFEF4444)
                else -> ArushiViolet
            }

            // Outer ring 1
            drawCircle(
                color = glowColor.copy(alpha = 0.15f * (1f + amplitude)),
                radius = dynamicRadius * 1.55f,
                center = center
            )

            // Outer ring 2
            drawCircle(
                color = glowColor.copy(alpha = 0.25f),
                radius = dynamicRadius * 1.25f,
                center = center
            )

            // Pulsating audio ripples when listening or speaking
            if (status == AssistantStatus.LISTENING || status == AssistantStatus.SPEAKING) {
                val waveRadius = dynamicRadius + (baseRadius * 0.8f * ripplePhase)
                drawCircle(
                    color = glowColor.copy(alpha = (1f - ripplePhase) * 0.4f),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Central rotating core gradient
            val angleRad = Math.toRadians(rotation.toDouble())
            val gradOffsetX = (cos(angleRad) * dynamicRadius).toFloat()
            val gradOffsetY = (sin(angleRad) * dynamicRadius).toFloat()

            val coreBrush = when (status) {
                AssistantStatus.LISTENING -> Brush.radialGradient(
                    colors = listOf(ArushiCyan, ArushiViolet, Color(0xFF091428)),
                    center = center,
                    radius = dynamicRadius
                )
                AssistantStatus.SPEAKING -> Brush.radialGradient(
                    colors = listOf(ArushiSaffron, ArushiVioletLight, Color(0xFF280B3B)),
                    center = center,
                    radius = dynamicRadius
                )
                AssistantStatus.THINKING, AssistantStatus.EXECUTING_ACTION -> Brush.linearGradient(
                    colors = listOf(ArushiVioletLight, ArushiCyan, ArushiSaffron),
                    start = center - Offset(gradOffsetX, gradOffsetY),
                    end = center + Offset(gradOffsetX, gradOffsetY)
                )
                else -> Brush.radialGradient(
                    colors = listOf(ArushiVioletLight, ArushiViolet, Color(0xFF140D2D)),
                    center = center,
                    radius = dynamicRadius
                )
            }

            // Inner Orb
            drawCircle(
                brush = coreBrush,
                radius = dynamicRadius,
                center = center
            )

            // Inner core glow dot
            drawCircle(
                color = Color.White.copy(alpha = 0.8f),
                radius = dynamicRadius * 0.22f,
                center = center
            )
        }
    }
}
