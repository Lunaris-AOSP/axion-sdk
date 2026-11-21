/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.axion.blur

import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.android.internal.graphics.AdvancedBlurConfig
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.toSize
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec
import com.android.axion.blur.ui.view.AxViewBackdropBlur
import com.android.axion.kotlin.settings.SettingsFlow
import com.android.axion.kotlin.settings.SettingsType
import kotlin.math.roundToInt

val LocalAxBlurEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberAxAdvancedBlurEnabled(
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
): Boolean {
    val context = LocalContext.current
    val settings = remember(context, settingsSpec) { AxBlurSettings(context, settingsSpec) }
    fun state() = settings.advancedBlurEnabled to AdvancedBlurConfig.current(context)
    var status by remember(settings) { mutableStateOf(state()) }
    DisposableEffect(settings) {
        settings.start(Runnable { status = state() })
        status = state()
        onDispose { settings.stop() }
    }
    return LocalAxBlurEnabled.current && status.first
}

@Composable
@ReadOnlyComposable
fun axAdvancedBlurContentColor(): Color {
    LocalConfiguration.current
    val context = LocalContext.current
    return Color(AdvancedBlurConfig.current(context)?.contentColor(context)
        ?: context.getColor(com.android.internal.R.color.materialColorOnSurface))
}

object AxBlurSurfaceDefaults {
    @Composable
    fun surfaceColor(): Color = colorResource(R.color.ax_blur_surface_bright_expressive)

    @Composable
    fun surfaceColor(alpha: Float): Color =
        surfaceColor().copy(alpha = alpha.coerceIn(0f, 1f))

    @Composable
    fun tintColor(surfaceColor: Color? = null): Color {
        val tint = colorResource(R.color.ax_blur_surface_bright_expressive_tint)
        return surfaceColor?.copy(alpha = tint.alpha) ?: tint
    }

    @Composable
    fun tintColor(alpha: Float, surfaceColor: Color? = null): Color {
        val tint = tintColor(surfaceColor)
        return tint.copy(alpha = tint.alpha * alpha.coerceIn(0f, 1f))
    }
}

@Composable
fun AxBlurLifecycle(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val parentEnabled = LocalAxBlurEnabled.current
    CompositionLocalProvider(
        LocalAxBlurEnabled provides (parentEnabled && enabled),
        content = content,
    )
}

@Composable
fun rememberAxBlurEnabled(
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
): Boolean {
    val resolver = LocalContext.current.contentResolver
    val globalSettings = remember(resolver) { SettingsFlow(resolver, SettingsType.GLOBAL) }
    val globalDefaultDisabled = !AxBlurProperties.defaultGlobalBlurEnabled
    val globalDisabled by remember(globalSettings, globalDefaultDisabled) {
        globalSettings.observeBoolean(Settings.Global.DISABLE_WINDOW_BLURS, globalDefaultDisabled)
    }.collectAsState(
        initial = globalSettings.getInt(
            Settings.Global.DISABLE_WINDOW_BLURS,
            if (globalDefaultDisabled) 1 else 0,
        ) != 0,
    )
    val enabledKey = settingsSpec.enabledKey
    val settingEnabled = if (enabledKey == null) {
        true
    } else {
        val enabledSettings = remember(resolver, settingsSpec.enabledSettingsType) {
            SettingsFlow(resolver, settingsSpec.enabledSettingsType)
        }
        val value by remember(enabledSettings, enabledKey, settingsSpec.defaultEnabled) {
            enabledSettings.observeBoolean(enabledKey, settingsSpec.defaultEnabled)
        }.collectAsState(
            initial = enabledSettings.getInt(
                enabledKey,
                if (settingsSpec.defaultEnabled) 1 else 0,
            ) != 0,
        )
        value
    }
    return !AxBlurProperties.disableBlur && !globalDisabled && settingEnabled
}

@Composable
fun AxBlurSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    cornerRadius: Dp,
    enabled: Boolean = true,
    surfaceColor: Color? = null,
    tintColor: Color? = null,
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val resolvedSurfaceColor = surfaceColor ?: AxBlurSurfaceDefaults.surfaceColor()
    val resolvedTintColor = tintColor ?: AxBlurSurfaceDefaults.tintColor(surfaceColor)
    Box(
        modifier = modifier
            .clip(shape)
            .axBlurBackground(
                enabled = enabled,
                fallbackColor = resolvedSurfaceColor,
                tintColor = resolvedTintColor,
                cornerRadius = cornerRadius,
                settingsSpec = settingsSpec,
                shape = shape,
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
fun Modifier.axBlurBackground(
    enabled: Boolean,
    fallbackColor: Color,
    tintColor: Color,
    cornerRadius: Dp = Dp.Unspecified,
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
    shape: Shape? = null,
): Modifier = axBlurBackground(enabled, fallbackColor, tintColor, cornerRadius, settingsSpec,
    shape, backdrop = false)

@Composable
fun Modifier.axBlurBackground(
    enabled: Boolean,
    fallbackColor: Color,
    tintColor: Color,
    cornerRadius: Dp = Dp.Unspecified,
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
    shape: Shape? = null,
    backdrop: Boolean,
): Modifier {
    val density = LocalDensity.current
    val view = LocalView.current
    val blurEnabled = LocalAxBlurEnabled.current && enabled
    val blur = remember(view, settingsSpec) {
        AxViewBackdropBlur(view).apply {
            useSettings(settingsSpec)
            setBackdropMaterial(backdrop)
            setEnabled(blurEnabled)
        }
    }
    val tintArgb = tintColor.toArgb()
    val cornerRadii = remember { FloatArray(8) }
    var sourceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val sourceMatrix = remember { androidx.compose.ui.graphics.Matrix() }
    val sourceAndroidMatrix = remember { android.graphics.Matrix() }
    val requestedCornerRadiusPx = if (cornerRadius.isSpecified) {
        with(density) { cornerRadius.toPx() }
    } else {
        Float.NaN
    }

    DisposableEffect(blur) {
        blur.onAttachedToWindow()
        onDispose {
            blur.onDetachedFromWindow()
        }
    }

    SideEffect {
        blur.setBackdropMaterial(backdrop)
        blur.setEnabled(blurEnabled)
    }

    val trackedModifier = axBlurFrameTracking(blurEnabled, blur)
        .onGloballyPositioned { sourceCoordinates = it }
    return trackedModifier.drawWithContent {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        val outline = shape?.createOutline(size, layoutDirection, this)
        val cornerRadiusPx = if (requestedCornerRadiusPx.isFinite()) {
            requestedCornerRadiusPx
        } else {
            size.minDimension * 0.5f
        }
        val drewBlur = if (blurEnabled && width > 0 && height > 0) {
            var result = false
            blur.setOverlayColor(tintArgb)
            sourceCoordinates?.takeIf { it.isAttached }?.let {
                sourceMatrix.reset()
                it.transformFrom(it.findRootCoordinates(), sourceMatrix)
                sourceAndroidMatrix.setFrom(sourceMatrix)
                blur.setSourceToLocalTransform(sourceAndroidMatrix, it.size.width, it.size.height)
            }
            drawIntoCanvas { canvas ->
                result = when (outline) {
                    is Outline.Rounded -> {
                        val rect = outline.roundRect
                        cornerRadii[0] = rect.topLeftCornerRadius.x
                        cornerRadii[1] = rect.topLeftCornerRadius.y
                        cornerRadii[2] = rect.topRightCornerRadius.x
                        cornerRadii[3] = rect.topRightCornerRadius.y
                        cornerRadii[4] = rect.bottomRightCornerRadius.x
                        cornerRadii[5] = rect.bottomRightCornerRadius.y
                        cornerRadii[6] = rect.bottomLeftCornerRadius.x
                        cornerRadii[7] = rect.bottomLeftCornerRadius.y
                        blur.draw(
                            canvas.nativeCanvas,
                            rect.left.roundToInt(), rect.top.roundToInt(),
                            rect.right.roundToInt(), rect.bottom.roundToInt(),
                            cornerRadii,
                        )
                    }
                    is Outline.Rectangle -> {
                        val rect = outline.rect
                        blur.draw(
                            canvas.nativeCanvas,
                            rect.left.roundToInt(), rect.top.roundToInt(),
                            rect.right.roundToInt(), rect.bottom.roundToInt(),
                            0f,
                        )
                    }
                    else -> blur.draw(
                        canvas.nativeCanvas,
                        0, 0, width, height,
                        cornerRadiusPx,
                    )
                }
            }
            result
        } else {
            false
        }
        if (!drewBlur) {
            val resolvedFallbackColor = Color(blur.resolveFallbackColor(fallbackColor.toArgb()))
            if (outline != null) {
                drawOutline(outline, color = resolvedFallbackColor)
            } else {
                drawRoundRect(
                    color = resolvedFallbackColor,
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
            }
        }
        drawContent()
    }
}

private fun Modifier.axBlurFrameTracking(enabled: Boolean, blur: AxViewBackdropBlur): Modifier =
    this then AxBlurFrameTrackingElement(enabled, blur)

private data class AxBlurFrameTrackingElement(
    val enabled: Boolean,
    val blur: AxViewBackdropBlur,
) : ModifierNodeElement<AxBlurFrameTrackingNode>() {
    override fun create() = AxBlurFrameTrackingNode(enabled, blur)

    override fun update(node: AxBlurFrameTrackingNode) {
        node.update(enabled, blur)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "axBlurFrameTracking"
        properties["enabled"] = enabled
    }
}

private class AxBlurFrameTrackingNode(
    private var enabled: Boolean,
    private var blur: AxViewBackdropBlur,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    LayoutAwareModifierNode,
    ObserverModifierNode {

    private var lastCoordinates: LayoutCoordinates? = null
    private var lastPosition = Offset.Unspecified
    private var lastSize = Size.Unspecified
    private var lastWindowId: Any? = null
    private var hostView: View? = null
    private var observingPreDraw = false
    private val drawInvalidation: () -> Unit = {
        if (isAttached && enabled) invalidateDraw()
    }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (isAttached && enabled) {
            lastCoordinates?.let { updateCoordinates(it) }
        }
        true
    }

    override val shouldAutoInvalidate = false

    fun update(enabled: Boolean, blur: AxViewBackdropBlur) {
        if (this.enabled == enabled && this.blur === blur) return
        if (this.blur.onInvalidate === drawInvalidation) this.blur.onInvalidate = null
        this.blur = blur
        this.enabled = enabled
        if (isAttached) blur.onInvalidate = drawInvalidation
        updatePreDrawObserver()
        invalidateDraw()
    }

    override fun onAttach() {
        blur.onInvalidate = drawInvalidation
        onObservedReadsChanged()
    }

    override fun onDetach() {
        if (blur.onInvalidate === drawInvalidation) blur.onInvalidate = null
        removePreDrawObserver()
        hostView = null
        lastCoordinates = null
        lastPosition = Offset.Unspecified
        lastSize = Size.Unspecified
        lastWindowId = null
    }

    override fun onObservedReadsChanged() {
        observeReads {
            updatePreDrawObserver()
            lastCoordinates?.let { updateCoordinates(it) }
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        updateCoordinates(coordinates)
    }

    override fun onRemeasured(size: IntSize) {
        val newSize = size.toSize()
        if (lastSize != newSize) {
            lastSize = newSize
            invalidateDraw()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        updateCoordinates(coordinates)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }

    private fun updateCoordinates(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        val bounds = coordinates.boundsInRoot()
        val newPosition = bounds.topLeft
        val newSize = bounds.size
        val newWindowId = currentWindowId()
        if (lastPosition != newPosition || lastSize != newSize || lastWindowId != newWindowId) {
            lastPosition = newPosition
            lastSize = newSize
            lastWindowId = newWindowId
            invalidateDraw()
        }
    }

    private fun currentWindowId(): Any? {
        return hostView?.windowId ?: currentValueOf(LocalView).windowId
    }

    private fun updatePreDrawObserver() {
        val view = if (isAttached && enabled) currentValueOf(LocalView) else null
        if (hostView !== view) {
            removePreDrawObserver()
            hostView = view
        }
        if (view != null && !observingPreDraw) {
            view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            observingPreDraw = true
        }
        if (view == null) {
            removePreDrawObserver()
        }
    }

    private fun removePreDrawObserver() {
        if (!observingPreDraw) return
        hostView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observingPreDraw = false
    }
}
