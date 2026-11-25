/*
 * Copyright (C) 2026 AxionOS
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

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import com.android.axion.blur.ui.view.AxBlurRimPainter
import com.android.internal.graphics.drawable.AxBlurCustomization

/** Draw a rim before this modifier's content, using the same shape as the blurred background. */
@Stable
fun Modifier.axBlurRim(
    rim: AxBlurRim = AxBlurRim.ColorOS,
    shape: Shape? = null,
    cornerRadius: Dp = Dp.Unspecified,
): Modifier = if (rim.enabled) this then AxBlurRimElement(rim, shape, cornerRadius) else this

internal fun Modifier.axBlurRimWithSettings(
    rim: AxBlurRim,
    shape: Shape? = null,
    cornerRadius: Dp = Dp.Unspecified,
): Modifier = this then AxBlurRimElement(rim, shape, cornerRadius, true)

private data class AxBlurRimElement(
    val rim: AxBlurRim,
    val shape: Shape?,
    val cornerRadius: Dp,
    val inheritSettings: Boolean = false,
) : ModifierNodeElement<AxBlurRimNode>() {
    override fun create() = AxBlurRimNode(rim, shape, cornerRadius, inheritSettings)

    override fun update(node: AxBlurRimNode) {
        node.rim = rim
        node.shape = shape
        node.cornerRadius = cornerRadius
        node.updateSettingsInheritance(inheritSettings)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "axBlurRim"
        properties["rim"] = rim
        properties["shape"] = shape
        properties["cornerRadius"] = cornerRadius
    }
}

private class AxBlurRimNode(
    var rim: AxBlurRim,
    var shape: Shape?,
    var cornerRadius: Dp,
    private var inheritSettings: Boolean,
) : Modifier.Node(), DrawModifierNode, ObserverModifierNode, CompositionLocalConsumerModifierNode {
    private val painter by lazy { AxBlurRimPainter() }
    private var customization: AxBlurCustomization? = null
    private var settings: AxBlurCustomization.State? = null
    private val settingsListener = Runnable {
        settings = customization?.state
        if (isAttached) invalidateDraw()
    }
    private val radii = FloatArray(8)
    private var cachedShape: Shape? = null
    private var cachedSize = Size.Unspecified
    private var cachedDensity = 0f
    private var cachedFontScale = 0f
    private var cachedDirection: LayoutDirection? = null
    private var outline: Outline? = null

    override fun onAttach() {
        startSettingsObserver()
    }

    override fun onDetach() {
        stopSettingsObserver()
    }

    fun updateSettingsInheritance(inherit: Boolean) {
        if (inheritSettings == inherit) return
        stopSettingsObserver()
        inheritSettings = inherit
        if (isAttached) startSettingsObserver()
    }

    private fun startSettingsObserver() {
        if (!inheritSettings) return
        customization = AxBlurCustomization.getInstance(currentValueOf(LocalContext))
        settings = customization?.state
        customization?.addListener(settingsListener)
    }

    private fun stopSettingsObserver() {
        customization?.removeListener(settingsListener)
        customization = null
        settings = null
    }

    override fun onObservedReadsChanged() {
        cachedSize = Size.Unspecified
        if (isAttached) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val resolvedRim = settings?.resolveRim(rim) ?: rim
        if (resolvedRim.enabled) {
            if (
                cachedShape != shape ||
                    cachedSize != size ||
                    cachedDensity != density ||
                    cachedFontScale != fontScale ||
                    cachedDirection != layoutDirection
            ) {
                if (isAttached) {
                    observeReads { outline = shape?.createOutline(size, layoutDirection, this) }
                } else {
                    outline = shape?.createOutline(size, layoutDirection, this)
                }
                cachedShape = shape
                cachedSize = size
                cachedDensity = density
                cachedFontScale = fontScale
                cachedDirection = layoutDirection
            }
            val radius = if (cornerRadius.isSpecified) cornerRadius.toPx() else 0f
            drawIntoCanvas { canvas ->
                when (val boundary = outline) {
                    is Outline.Rounded -> {
                        val rect = boundary.roundRect
                        radii[0] = rect.topLeftCornerRadius.x
                        radii[1] = rect.topLeftCornerRadius.y
                        radii[2] = rect.topRightCornerRadius.x
                        radii[3] = rect.topRightCornerRadius.y
                        radii[4] = rect.bottomRightCornerRadius.x
                        radii[5] = rect.bottomRightCornerRadius.y
                        radii[6] = rect.bottomLeftCornerRadius.x
                        radii[7] = rect.bottomLeftCornerRadius.y
                        painter.draw(
                            canvas.nativeCanvas,
                            resolvedRim,
                            density,
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom,
                            cornerRadii = radii,
                        )
                    }
                    is Outline.Rectangle -> {
                        val rect = boundary.rect
                        painter.draw(
                            canvas.nativeCanvas,
                            resolvedRim,
                            density,
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom,
                        )
                    }
                    is Outline.Generic ->
                        painter.draw(
                            canvas.nativeCanvas,
                            resolvedRim,
                            density,
                            0f,
                            0f,
                            size.width,
                            size.height,
                            clipPath = boundary.path.asAndroidPath(),
                        )
                    null ->
                        painter.draw(
                            canvas.nativeCanvas,
                            resolvedRim,
                            density,
                            0f,
                            0f,
                            size.width,
                            size.height,
                            cornerRadius = radius,
                        )
                }
            }
        }
        drawContent()
    }
}
