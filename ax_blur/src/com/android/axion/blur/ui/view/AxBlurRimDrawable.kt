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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.android.axion.blur.ui.view.AxBlurRimPainter

/** A rim-only drawable, suitable for the last layer of a surface's background. */
open class AxBlurRimDrawable
@JvmOverloads
constructor(private val density: Float, rim: AxBlurRim = AxBlurRim.ColorOS) : Drawable() {
    private val painter = AxBlurRimPainter()
    private var drawableAlpha = 255
    private var filter: ColorFilter? = null
    private var radii: FloatArray? = null
    private var clipPath: Path? = null

    /** Optional outline in drawable-bounds coordinates, copied so callers can safely reuse it. */
    fun setClipPath(path: Path?) {
        clipPath = path?.let { Path(it) }
        invalidateSelf()
    }

    init {
        require(density.isFinite() && density > 0f) { "Rim density must be positive" }
    }

    var rim: AxBlurRim = rim
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    var cornerRadiusPx: Float = 0f
        set(value) {
            require(value.isFinite() && value >= 0f) { "Rim corner radius must be nonnegative" }
            if (field == value) return
            field = value
            invalidateSelf()
        }

    /** Radii use Android's TL, TR, BR, BL order, with x/y components for each corner. */
    fun setCornerRadii(cornerRadii: FloatArray?) {
        require(
            cornerRadii == null ||
                (cornerRadii.size >= 8 && cornerRadii.all { it.isFinite() && it >= 0f })
        ) {
            "Rim corner radii require eight nonnegative values"
        }
        if (radii.contentEquals(cornerRadii)) return
        radii = cornerRadii?.copyOfRange(0, 8)
        invalidateSelf()
    }

    fun setRimEnabled(enabled: Boolean) {
        rim = rim.copy(enabled = enabled)
    }

    override fun draw(canvas: Canvas) {
        val rect = bounds
        painter.draw(
            canvas,
            rim,
            density,
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            cornerRadiusPx,
            radii,
            clipPath = clipPath,
            alpha = drawableAlpha,
            colorFilter = filter,
        )
    }

    override fun setAlpha(alpha: Int) {
        val value = alpha.coerceIn(0, 255)
        if (drawableAlpha == value) return
        drawableAlpha = value
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter(): ColorFilter? = filter

    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
