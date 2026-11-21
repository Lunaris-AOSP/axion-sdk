/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.axion.blur

import android.content.Context
import android.graphics.RenderEffect
import com.android.internal.graphics.AdvancedBlurConfig
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import kotlin.math.ceil
import kotlin.math.min

internal object AxAdvancedBlur {
    fun radiusPx(context: Context, backdrop: Boolean = false): Float {
        val preset = AdvancedBlurConfig.current(context)
        val radiusDp = if (backdrop) preset?.backdropBlurDp ?: 0f else preset?.blurDp ?: 0f
        return radiusDp * context.resources.displayMetrics.density
    }

    fun apply(
        drawable: BackgroundBlurDrawable,
        context: Context,
        enabled: Boolean,
        backdrop: Boolean = false,
    ) {
        val preset = if (enabled) AdvancedBlurConfig.current(context) else null
        val density = context.resources.displayMetrics.density
        if (preset != null) {
            val tint = AxBlurColors.materialTint(context)
            drawable.setAdvancedBlurShader(
                preset.shaderSource(context, tint, backdrop),
                density,
                tint,
                !backdrop && preset.usesSharpContent(),
            )
        } else {
            drawable.setGlassBlur(BackgroundBlurDrawable.GLASS_MODE_NONE, density)
        }
    }

    fun sourceOutset(
        context: Context,
        radius: Float,
        advanced: Boolean,
        backdrop: Boolean = false,
    ): Int {
        val kernel =
            if (radius.isFinite() && radius > 0f) {
                3f * min(28f, 0.57735f * radius + 0.5f)
            } else 0f
        val padding =
            if (advanced && !backdrop) {
                (AdvancedBlurConfig.current(context)?.sourcePaddingDp ?: 0f) *
                    context.resources.displayMetrics.density
            } else 0f
        return ceil(kernel + padding).toInt().coerceAtLeast(0)
    }
}

internal class AxAdvancedBlurEffect(private val context: Context) {
    private val effect = AdvancedBlurConfig.Effect()

    fun resolve(
        blurRadius: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float = 0f,
        cornerRadii: FloatArray? = null,
        backdrop: Boolean = false,
    ): RenderEffect? {
        fun corner(index: Int): Float =
            if (cornerRadii != null && cornerRadii.size >= 8) {
                (cornerRadii[index] + cornerRadii[index + 1]) * 0.5f
            } else {
                cornerRadius
            }
        return effect.resolve(
            context,
            AdvancedBlurConfig.current(context),
            AxBlurColors.materialTint(context),
            blurRadius,
            left,
            top,
            right,
            bottom,
            corner(0),
            corner(2),
            corner(4),
            corner(6),
            backdrop,
        )
    }
}
