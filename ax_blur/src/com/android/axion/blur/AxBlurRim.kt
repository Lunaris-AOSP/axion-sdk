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

import android.graphics.Color
import androidx.compose.runtime.Immutable

/**
 * An optional inner highlight, independent of blur radius and tint customization.
 *
 * Dimensions are in dp on both View and Compose surfaces. Alpha values and [strength] are in 0..1;
 * the color's own alpha also multiplies the result. [glowDepthDp] is the total inward glow depth,
 * independent of [edgeWidthDp]. [glowAlpha] scales the glow relative to the edge highlight.
 *
 * [cornerWidthScale] and [cornerAlphaScale] apply at the diagonal of a rounded corner and blend
 * smoothly back to full width/alpha at its tangents. A scale of 1 disables that taper; 0 removes
 * the highlight at the diagonal. [cornerTaperPower] controls how narrowly the taper is confined to
 * the diagonal (larger values produce a narrower tapered region).
 */
@Immutable
data class AxBlurRim
@JvmOverloads
constructor(
    val enabled: Boolean = true,
    val color: Int = Color.WHITE,
    val strength: Float = 1f,
    val topAlpha: Float = 0.14f,
    val bottomAlpha: Float = 0.5f,
    val sideAlpha: Float = 0f,
    val edgeWidthDp: Float = 0.8f,
    val glowDepthDp: Float = 12.5f,
    val glowAlpha: Float = 0.3f,
    val cornerWidthScale: Float = 1f,
    val cornerAlphaScale: Float = 0.8f,
    val cornerTaperPower: Float = 6.8f,
) {
    init {
        for (value in
            floatArrayOf(
                strength,
                topAlpha,
                bottomAlpha,
                sideAlpha,
                glowAlpha,
                cornerWidthScale,
                cornerAlphaScale,
            )) {
            require(value.isFinite() && value in 0f..1f) { "Rim alpha and scale must be in 0..1" }
        }
        require(edgeWidthDp.isFinite() && edgeWidthDp >= 0f) {
            "Rim edge width must be nonnegative"
        }
        require(glowDepthDp.isFinite() && glowDepthDp >= 0f) {
            "Rim glow depth must be nonnegative"
        }
        require(cornerTaperPower.isFinite() && cornerTaperPower > 0f) {
            "Rim corner taper power must be positive"
        }
    }

    companion object {
        /** Existing surfaces keep their current appearance until explicitly enabled. */
        @JvmField val None = AxBlurRim(enabled = false)

        /** Starting preset based on the subtle top rim and brighter bottom notification rim. */
        @JvmField val ColorOS = AxBlurRim()
    }
}
