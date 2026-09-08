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

import com.android.axion.blur.AxBlurProperties

/**
 * Tunables and support gating for the Liquid Glass refraction effect.
 *
 * These constants define the "balanced" intensity profile: refraction derived
 * from the surface corner radius, depth effect on, dispersion off. They are the
 * single place to adjust the system-wide glass look.
 */
internal object AxGlassConfig {

    /**
     * Fraction of the corner radius used as the refracting edge band thickness.
     * Larger values push the distortion further toward the center.
     */
    const val REFRACTION_HEIGHT_FRACTION = 1.0f

    /**
     * Displacement magnitude relative to the refraction height. ~1.0 keeps the
     * lensing proportional to the edge band for a natural glass thickness.
     */
    const val REFRACTION_AMOUNT_FRACTION = 1.0f

    /** Lower bound (px) below which refraction is not worth the extra pass. */
    const val MIN_REFRACTION_HEIGHT_PX = 2.0f

    /** Depth effect bends the gradient toward the center for a lens-like look. */
    const val DEPTH_EFFECT = true

    /**
     * Whether the glass refraction effect can run at all. The ROM targets a
     * platform SDK with AGSL runtime shaders always available, so this only
     * honors the kill-switch property.
     */
    fun isSupported(): Boolean = !AxBlurProperties.disableGlass
}
