/*
 * Copyright (C) 2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.blur

import com.android.internal.graphics.drawable.AxBlurCustomization

internal fun AxBlurCustomization.State.resolveRim(requested: AxBlurRim): AxBlurRim =
    if (rim.enabled) AxBlurRimSettings.resolve(rim) else requested

private object AxBlurRimSettings {
    private var config: AxBlurCustomization.Rim? = null
    private var style = AxBlurRim.None

    @Synchronized
    fun resolve(value: AxBlurCustomization.Rim): AxBlurRim {
        if (config !== value) {
            style =
                AxBlurRim(
                    enabled = value.enabled,
                    color = value.color,
                    strength = value.strength,
                    topAlpha = value.topAlpha,
                    bottomAlpha = value.bottomAlpha,
                    sideAlpha = value.sideAlpha,
                    edgeWidthDp = value.edgeWidthDp,
                    glowDepthDp = value.glowDepthDp,
                    glowAlpha = value.glowAlpha,
                    cornerWidthScale = value.cornerWidthScale,
                    cornerAlphaScale = value.cornerAlphaScale,
                    cornerTaperPower = value.cornerTaperPower,
                )
            config = value
        }
        return style
    }
}
