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

package com.android.axion.blur.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import kotlin.math.min

/**
 * Builds the Liquid Glass [RenderEffect]: a rounded-rect refraction runtime
 * shader chained on top of an existing (plain) blur effect so the shader
 * samples the already-blurred backdrop.
 *
 * One instance is held per blur surface. It caches a single [RuntimeShader] and
 * the last built [RenderEffect], rebuilding only when the geometry inputs
 * (blur radius, size, corner radii, outset) change, so no shader allocation
 * happens per frame.
 *
 * All callers must fall back to their plain blur effect when [build] returns
 * null (unsupported API, glass disabled, or a non-rounded surface).
 */
internal class AxGlassRenderEffect {

    private var shader: RuntimeShader? = null
    private var cachedEffect: RenderEffect? = null
    private var cachedKey: Key? = null

    /**
     * @param blur the plain blur effect to refract; sampled as the shader content.
     * @param width layer/shape width in px.
     * @param height layer/shape height in px.
     * @param cornerRadii per-corner radii in px, order [topLeft, topRight,
     *   bottomRight, bottomLeft]. Uniform radius = all four equal.
     * @param outsetX extra transparent border (px) recorded to the left/right of
     *   the content, if any (blur outset). 0 for the common case.
     * @param outsetY extra transparent border (px) recorded above/below the
     *   content, if any (blur outset). 0 for the common case.
     * @return the chained glass effect, or null if the surface can't be glassed
     *   (caller should use [blur] as-is).
     */
    fun build(
        blur: RenderEffect,
        width: Float,
        height: Float,
        cornerRadii: FloatArray,
        outsetX: Float = 0f,
        outsetY: Float = outsetX,
    ): RenderEffect? {
        if (!AxGlassConfig.isSupported()) return null
        if (width <= 0f || height <= 0f) return null
        if (cornerRadii.size < 4) return null

        val maxRadius = min(width, height) * 0.5f
        val r0 = cornerRadii[0].sanitizeRadius(maxRadius)
        val r1 = cornerRadii[1].sanitizeRadius(maxRadius)
        val r2 = cornerRadii[2].sanitizeRadius(maxRadius)
        val r3 = cornerRadii[3].sanitizeRadius(maxRadius)

        val largestRadius = maxOf(r0, r1, r2, r3)
        if (largestRadius <= 0f) return null

        val refractionHeight = (largestRadius * AxGlassConfig.REFRACTION_HEIGHT_FRACTION)
            .coerceAtMost(maxRadius)
        if (refractionHeight < AxGlassConfig.MIN_REFRACTION_HEIGHT_PX) return null
        val refractionAmount = refractionHeight * AxGlassConfig.REFRACTION_AMOUNT_FRACTION

        val key = Key(width, height, r0, r1, r2, r3, outsetX, outsetY)
        cachedEffect?.let { if (cachedKey == key) return it }

        val runtimeShader = shader
            ?: RuntimeShader(AxGlassRefractionShaderString).also { shader = it }

        runtimeShader.setFloatUniform("size", width, height)
        runtimeShader.setFloatUniform("offset", -outsetX, -outsetY)
        runtimeShader.setFloatUniform("cornerRadii", r0, r1, r2, r3)
        runtimeShader.setFloatUniform("refractionHeight", refractionHeight)
        // Negated to match the reference Lens implementation's sign convention.
        runtimeShader.setFloatUniform("refractionAmount", -refractionAmount)
        runtimeShader.setFloatUniform(
            "depthEffect",
            if (AxGlassConfig.DEPTH_EFFECT) 1f else 0f,
        )

        val refractionEffect = RenderEffect.createRuntimeShaderEffect(runtimeShader, "content")
        // inner (blur) is applied first, outer (refraction) samples the result.
        val effect = RenderEffect.createChainEffect(refractionEffect, blur)

        cachedEffect = effect
        cachedKey = key
        return effect
    }

    /** Builds from a single uniform corner radius. */
    fun build(
        blur: RenderEffect,
        width: Float,
        height: Float,
        cornerRadius: Float,
        outsetX: Float = 0f,
        outsetY: Float = outsetX,
    ): RenderEffect? {
        if (cornerRadius <= 0f) return null
        return build(
            blur,
            width,
            height,
            floatArrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius),
            outsetX,
            outsetY,
        )
    }

    fun reset() {
        cachedEffect = null
        cachedKey = null
    }

    private fun Float.sanitizeRadius(maxRadius: Float): Float {
        if (!isFinite() || this <= 0f) return 0f
        return coerceAtMost(maxRadius)
    }

    private data class Key(
        val width: Float,
        val height: Float,
        val r0: Float,
        val r1: Float,
        val r2: Float,
        val r3: Float,
        val outsetX: Float,
        val outsetY: Float,
    )
}

/**
 * Extracts the four SDF corner radii [topLeft, topRight, bottomRight,
 * bottomLeft] from an Android [android.graphics.drawable.GradientDrawable]-style
 * 8-value radii array (x/y pairs per corner). Returns null if the array is not
 * the expected size.
 */
internal fun FloatArray.toGlassCornerRadii(): FloatArray? {
    if (size < 8) return null
    // GradientDrawable order is TL, TR, BR, BL as (x, y) pairs; take the x of each.
    return floatArrayOf(this[0], this[2], this[4], this[6])
}
