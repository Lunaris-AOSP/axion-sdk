/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.axion.blur

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.graphics.AdvancedBlurConfig

/** Keep neutral foregrounds readable on the preset's palette, preserving semantic colors. */
class AxBlurForeground(private val root: View) {
    val fallbackForegroundColor: Int
        get() = forcedColor ?: Color.TRANSPARENT

    private val colors = mutableMapOf<View, Colors>()
    private val hintColors = mutableMapOf<View, Colors>()
    private var forcedColor: Int? = null
    private var uiMode = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    private var fallbackSource: Drawable? = null
    private var fallback: Drawable? = null
    private val layoutChanged =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (forcedColor != null) update(true)
        }

    private inner class Colors(val view: View, initial: ColorStateList, val hint: Boolean) :
        View.OnAttachStateChangeListener {
        var original = initial
        val followsSystemPalette =
            isLight(initial.defaultColor) ==
                isLight(root.context.getColor(com.android.internal.R.color.materialColorOnSurface))
        var applied: ColorStateList? = null

        init {
            view.addOnAttachStateChangeListener(this)
        }

        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) = restore()

        fun restore() {
            if (applied != null && currentColors(view, hint) === applied)
                setColors(view, original, hint)
            view.removeOnAttachStateChangeListener(this)
            (if (hint) hintColors else colors).remove(view)
        }
    }

    fun update(enabled: Boolean) {
        val context = root.context
        val config = if (enabled) AdvancedBlurConfig.current(context) else null
        val color = config?.contentColor(context)
        refreshPaletteBaseline()
        if (color == null) {
            restore()
            return
        }
        if (forcedColor == null) root.addOnLayoutChangeListener(layoutChanged)
        forcedColor = color
        updateView(root, isLight(color))
    }

    private fun updateView(view: View, light: Boolean) {
        if (view is TextView && view !is Button) {
            updateColors(view, view.textColors, light)
            view.hintTextColors?.let { updateColors(view, it, light, hint = true) }
        } else if (view is ImageView && view.imageTintList != null) {
            updateColors(view, view.imageTintList!!, light)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) updateView(view.getChildAt(index), light)
        }
    }

    private fun updateColors(
        view: View,
        current: ColorStateList,
        light: Boolean,
        hint: Boolean = false,
    ) {
        val tracked = if (hint) hintColors else colors
        val existing = tracked[view]
        val original =
            if (existing != null && current === existing.applied) existing.original else current
        val color = original.defaultColor
        // Preserve branded/action colors and explicit colors already suitable for this palette.
        if (!isNeutral(color) || isLight(color) == light) {
            existing?.restore()
            return
        }
        val state = existing ?: Colors(view, original, hint).also { tracked[view] = it }
        if (state.applied == null || state.original !== original) {
            state.original = original
            state.applied = invert(original)
        }
        if (current !== state.applied) setColors(view, state.applied!!, hint)
    }

    private fun isLight(color: Int): Boolean =
        maxOf(Color.red(color), Color.green(color), Color.blue(color)) +
            minOf(Color.red(color), Color.green(color), Color.blue(color)) >= 255

    private fun isNeutral(color: Int): Boolean =
        maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
            minOf(Color.red(color), Color.green(color), Color.blue(color)) <= 32

    private fun currentColors(view: View, hint: Boolean): ColorStateList? =
        when (view) {
            is TextView -> if (hint) view.hintTextColors else view.textColors
            is ImageView -> view.imageTintList
            else -> null
        }

    private fun setColors(view: View, color: ColorStateList, hint: Boolean) {
        if (view is TextView) {
            if (hint) view.setHintTextColor(color) else view.setTextColor(color)
        } else if (view is ImageView) view.imageTintList = color
    }

    private fun invert(original: ColorStateList) =
        ColorStateList(
            original.states,
            original.colors
                .map { color ->
                    if (!isNeutral(color)) color
                    else
                        Color.argb(
                            Color.alpha(color),
                            255 - Color.red(color),
                            255 - Color.green(color),
                            255 - Color.blue(color),
                        )
                }
                .toIntArray(),
        )

    /** Keep software snapshots readable without changing the on-screen foregrounds. */
    fun drawFallback(canvas: Canvas, original: Drawable) {
        val color = forcedColor
        if (color == null) {
            original.draw(canvas)
            return
        }
        if (fallbackSource !== original) {
            fallbackSource = original
            fallback = original.constantState?.newDrawable(root.resources)?.mutate()
        }
        val background =
            fallback
                ?: run {
                    original.draw(canvas)
                    return
                }
        val tint =
            root.context.getColor(
                if (isLight(color)) android.R.color.system_neutral1_900
                else android.R.color.system_neutral1_50
            )
        tintFill(background, tint)
        background.bounds = original.bounds
        background.alpha = original.alpha
        background.state = original.state
        background.level = original.level
        background.draw(canvas)
    }

    private fun tintFill(drawable: Drawable, color: Int): Boolean =
        when (drawable) {
            is GradientDrawable -> {
                if (drawable.color?.defaultColor == Color.TRANSPARENT) false
                else {
                    val alpha = drawable.color?.defaultColor?.let(Color::alpha) ?: 255
                    drawable.setColor((color and 0x00ffffff) or (alpha shl 24))
                    true
                }
            }
            is ColorDrawable -> {
                if (drawable.color == Color.TRANSPARENT) false
                else {
                    drawable.color = (color and 0x00ffffff) or (Color.alpha(drawable.color) shl 24)
                    true
                }
            }
            is ShapeDrawable -> {
                val alpha = Color.alpha(drawable.paint.color)
                if (alpha == 0) false
                else {
                    drawable.paint.color = (color and 0x00ffffff) or (alpha shl 24)
                    true
                }
            }
            is DrawableWrapper -> drawable.drawable?.let { tintFill(it, color) } ?: false
            is LayerDrawable -> {
                var tinted = false
                for (index in 0 until drawable.numberOfLayers) {
                    if (
                        drawable.getId(index) != android.R.id.mask &&
                            tintFill(drawable.getDrawable(index), color)
                    ) {
                        tinted = true
                        break
                    }
                }
                tinted
            }
            else -> false
        }

    fun restore() {
        refreshPaletteBaseline()
        if (forcedColor != null) root.removeOnLayoutChangeListener(layoutChanged)
        forcedColor = null
        colors.values.toList().forEach { it.restore() }
        hintColors.values.toList().forEach { it.restore() }
        fallbackSource = null
        fallback = null
    }

    private fun refreshPaletteBaseline() {
        val mode = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (mode != uiMode) {
            (colors.values + hintColors.values).forEach { state ->
                val current = currentColors(state.view, state.hint)
                if (current === state.applied) {
                    if (state.followsSystemPalette) state.original = invert(state.original)
                    // Reset the old override before discarding its identity. Otherwise it can
                    // become the new baseline, or survive when blur is disabled in night mode.
                    setColors(state.view, state.original, state.hint)
                } else if (current != null) {
                    state.original = current
                }
                state.applied = null
            }
            uiMode = mode
        }
    }
}
