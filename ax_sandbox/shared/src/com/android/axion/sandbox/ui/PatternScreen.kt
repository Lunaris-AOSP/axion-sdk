package com.android.axion.sandbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.axion.sandbox.security.SandboxSecurityManager.BiometricType
import com.android.axion.sandbox.security.SandboxSecurityManager.BiometricType.NONE
import com.android.axion.sandbox.security.SandboxSecurityManager.BiometricType.FACE
import com.android.axion.sandbox.shared.R
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun PatternScreen(
    isSetup: Boolean = false,
    promptText: String? = null,
    onUnlock: () -> Unit,
    onPatternEntered: (List<Int>) -> Boolean,
    confirmPattern: List<Int>? = null,
    onBack: (() -> Unit)? = null,
    biometricType: BiometricType = NONE,
    onBiometricClick: () -> Unit = {},
    onForgotPassword: (() -> Unit)? = null,
    isExiting: Boolean = false
) {
    var selectedDots by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentTouchPosition by remember { mutableStateOf<Offset?>(null) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var dotPositions by remember { mutableStateOf<Map<Int, Offset>>(emptyMap()) }
    val haptic = LocalHapticFeedback.current
    val errorMismatch = stringResource(R.string.pattern_error_mismatch)
    val errorIncorrect = stringResource(R.string.pattern_error_incorrect)
    val errorTooShort = stringResource(R.string.pattern_error_too_short)

    val contentAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(250),
        label = "contentAlpha"
    )

    LaunchedEffect(confirmPattern) {
        selectedDots = emptyList()
        isError = false
    }

    val shakeOffset by animateFloatAsState(
        targetValue = if (isError) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "shake",
        finishedListener = {
            if (isError) {
                isError = false
                selectedDots = emptyList()
                errorMessage = ""
            }
        }
    )

    val shakeTranslation = if (isError) {
        sin(shakeOffset * 4 * PI.toFloat()) * 16f
    } else 0f

    val submitPattern = {
        if (selectedDots.size >= 4) {
            val success = onPatternEntered(selectedDots)
            if (success) {
                onUnlock()
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                errorMessage = if (isSetup && confirmPattern != null) errorMismatch else errorIncorrect
                isError = true
            }
        } else if (selectedDots.isNotEmpty()) {
            errorMessage = errorTooShort
            isError = true
        }
    }

    val density = LocalDensity.current
    val dotRadius = with(density) { 12.dp.toPx() }
    val touchRadius = with(density) { 40.dp.toPx() }

    val errorColor = MaterialTheme.colorScheme.error
    val lineColor = if (isError) errorColor else MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary
    val dotInactiveColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .graphicsLayer { alpha = contentAlpha }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        ) {
            Text(
                text = if (isSetup) {
                    if (confirmPattern == null) stringResource(R.string.pattern_title_create)
                    else stringResource(R.string.pattern_title_confirm)
                } else stringResource(R.string.pattern_title_enter),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = promptText ?: if (isSetup) {
                    if (confirmPattern == null) stringResource(R.string.pattern_prompt_create)
                    else stringResource(R.string.pattern_prompt_confirm)
                } else stringResource(R.string.pattern_prompt_unlock),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isError) errorMessage else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.height(20.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size(280.dp)
                    .graphicsLayer { translationX = shakeTranslation }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isError = false
                                errorMessage = ""
                                selectedDots = emptyList()
                                currentTouchPosition = offset

                                val hitDot = findHitDot(offset, dotPositions, touchRadius)
                                if (hitDot != null && !selectedDots.contains(hitDot)) {
                                    selectedDots = listOf(hitDot)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDrag = { change, _ ->
                                currentTouchPosition = change.position

                                val hitDot = findHitDot(change.position, dotPositions, touchRadius)
                                if (hitDot != null && !selectedDots.contains(hitDot)) {
                                    selectedDots = selectedDots + hitDot
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                currentTouchPosition = null
                                submitPattern()
                            },
                            onDragCancel = {
                                currentTouchPosition = null
                                selectedDots = emptyList()
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gridSize = 3
                    val cellSize = size.width / gridSize

                    val positions = mutableMapOf<Int, Offset>()
                    for (row in 0 until gridSize) {
                        for (col in 0 until gridSize) {
                            val dotIndex = row * gridSize + col
                            val center = Offset(
                                x = col * cellSize + cellSize / 2,
                                y = row * cellSize + cellSize / 2
                            )
                            positions[dotIndex] = center
                        }
                    }
                    dotPositions = positions

                    if (selectedDots.size > 1) {
                        for (i in 0 until selectedDots.size - 1) {
                            val from = positions[selectedDots[i]]!!
                            val to = positions[selectedDots[i + 1]]!!
                            drawLine(
                                color = lineColor,
                                start = from,
                                end = to,
                                strokeWidth = 8.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    if (selectedDots.isNotEmpty() && currentTouchPosition != null) {
                        val lastDot = positions[selectedDots.last()]!!
                        drawLine(
                            color = lineColor.copy(alpha = 0.5f),
                            start = lastDot,
                            end = currentTouchPosition!!,
                            strokeWidth = 8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    for ((index, dotCenter) in positions) {
                        val isSelected = selectedDots.contains(index)
                        val color = when {
                            isError -> errorColor
                            isSelected -> dotColor
                            else -> dotInactiveColor
                        }

                        drawCircle(
                            color = color.copy(alpha = if (isSelected) 0.3f else 0.2f),
                            radius = dotRadius * 2.5f,
                            center = dotCenter
                        )

                        drawCircle(
                            color = color,
                            radius = if (isSelected) dotRadius * 1.3f else dotRadius,
                            center = dotCenter
                        )
                    }
                }
            }

            if (!isSetup && biometricType != NONE) {
                Spacer(modifier = Modifier.height(24.dp))
                IconButton(
                    onClick = onBiometricClick,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        imageVector = if (biometricType == FACE) Icons.Filled.Face else Icons.Filled.Fingerprint,
                        contentDescription = stringResource(R.string.action_biometric_unlock),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (!isSetup && onForgotPassword != null) {
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = onForgotPassword) {
                    Text(
                        text = stringResource(R.string.action_forgot_password),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun findHitDot(
    position: Offset,
    dotPositions: Map<Int, Offset>,
    touchRadius: Float
): Int? {
    for ((index, center) in dotPositions) {
        val distance = sqrt(
            (position.x - center.x).pow(2) + (position.y - center.y).pow(2)
        )
        if (distance <= touchRadius) {
            return index
        }
    }
    return null
}
