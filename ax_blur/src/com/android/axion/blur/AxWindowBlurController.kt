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

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import com.android.axion.blur.domain.interactor.AxBackdropBlurInteractor
import com.android.axion.blur.model.AxBackdropBlurSettingsModel
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec
import com.android.internal.graphics.drawable.AxBlurCustomization
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import java.util.function.Consumer
import kotlin.math.roundToInt

class AxWindowBlurController @JvmOverloads constructor(
    private val window: Window,
    context: Context? = null,
    private val blurBehind: Boolean = false,
    private val blurBehindRadiusScale: Float = DEFAULT_BLUR_BEHIND_RADIUS_SCALE,
    private val surfaceAlpha: Int = DEFAULT_SURFACE_ALPHA,
    settingsSpec: AxBackdropBlurSettingsSpec = AxBackdropBlurSettingsSpec.system(),
) {
    private val context = context ?: window.context
    private val blurInteractor = AxBackdropBlurInteractor(
        this.context, settingsSpec,
    )
    private val settingsObserver = blurInteractor.createSubscription {
        apply()
    }
    private val surfaces = LinkedHashMap<View, SurfaceBlurState>()
    private val pendingPreDrawListeners = ArrayList<PendingPreDraw>()
    private var started = false
    private var rim = AxBlurRim.None
    private val windowManager = this.context.getSystemService(WindowManager::class.java)
    private val crossWindowBlurListener = Consumer<Boolean> {
        if (started) apply()
    }
    private var observingSurfaceChanges = false
    private val surfaceChangeListener = ViewTreeObserver.OnPreDrawListener {
        if (started && surfaces.any { (view, state) -> state.hasExternalBackground(view) }) apply()
        true
    }

    fun start() {
        if (!started) {
            started = true
            settingsObserver.start()
            windowManager?.addCrossWindowBlurEnabledListener(context.mainExecutor, crossWindowBlurListener)
        }
        apply()
    }

    fun stop() {
        if (started) {
            started = false
            settingsObserver.stop()
            windowManager?.removeCrossWindowBlurEnabledListener(crossWindowBlurListener)
        }
        updateSurfaceChangeObserver(false)
        clearPendingPreDrawListeners()
        clearWindowBlur()
        restoreSurfaces()
    }

    fun apply() {
        apply(blurInteractor.settings())
    }

    fun getRim(): AxBlurRim = rim

    fun setRim(rim: AxBlurRim) {
        if (this.rim == rim) return
        this.rim = rim
        if (started) apply()
    }

    fun setRimEnabled(enabled: Boolean) {
        setRim(rim.copy(enabled = enabled))
    }

    fun addSurface(view: View?) {
        if (view == null || surfaces.containsKey(view)) return
        surfaces[view] = SurfaceBlurState.capture(view)
        if (started) apply()
    }

    fun applyOnNextDraw(trigger: View?) {
        if (trigger == null) return
        val observer = trigger.viewTreeObserver
        if (!observer.isAlive) return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePendingPreDrawListener(trigger, observer, this)
                apply()
                return true
            }
        }
        pendingPreDrawListeners.add(PendingPreDraw(trigger, observer, listener))
        observer.addOnPreDrawListener(listener)
    }

    fun detach() {
        stop()
        surfaces.clear()
    }

    private fun apply(settings: AxBackdropBlurSettingsModel) {
        val radius = if (settings.enabled) {
            settings.customization.resolveRadius(context, settings.blurRadiusPx).roundToInt()
        } else {
            0
        }
        val blurSupported = AxBlurSupport.supportsCrossWindowBlur() && !AxBlurProperties.disableBlur
        val supportedRadius = if (blurSupported) {
            radius
        } else {
            0
        }
        val resolvedRim = settings.customization.resolveRim(rim)
        updateSurfaceChangeObserver(started && (settings.customization.tintEnabled || resolvedRim.enabled) && surfaces.isNotEmpty())
        val alpha = if (supportedRadius > 0) surfaceAlpha.sanitizedAlpha() else 255
        applyWindowBlur(supportedRadius, settings.customization.radiusEnabled)
        applySurfaceBlur(
            supportedRadius, alpha,
            settings.customization.takeIf { settings.enabled && blurSupported },
            resolvedRim,
        )
    }

    private fun updateSurfaceChangeObserver(observe: Boolean) {
        if (observingSurfaceChanges == observe) return
        observingSurfaceChanges = observe
        val observer = window.decorView.viewTreeObserver
        if (!observer.isAlive) return
        if (observe) observer.addOnPreDrawListener(surfaceChangeListener)
        else observer.removeOnPreDrawListener(surfaceChangeListener)
    }

    private fun applyWindowBlur(radius: Int, overrideRadius: Boolean = false) {
        val backgroundRadius = if (blurBehind) radius.coerceAtLeast(0) else 0
        setWindowBackgroundBlurRadius(backgroundRadius)
        val behindRadius =
            (backgroundRadius * if (overrideRadius) 1f else blurBehindRadiusScale).roundToInt().coerceAtLeast(0)
        val attrs = window.attributes
        val flags = if (backgroundRadius > 0) {
            attrs.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
        if (attrs.blurBehindRadius != behindRadius || attrs.flags != flags) {
            attrs.setBlurBehindRadius(behindRadius)
            attrs.flags = flags
            window.attributes = attrs
        }
    }

    private fun clearWindowBlur() {
        applyWindowBlur(0)
    }

    private fun setWindowBackgroundBlurRadius(radius: Int) {
        if (window.peekDecorView() != null) {
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun applySurfaceBlur(radius: Int, alpha: Int, customization: AxBlurCustomization.State?, resolvedRim: AxBlurRim) {
        surfaces.forEach { (view, state) ->
            state.apply(view, context, radius, alpha, customization, resolvedRim)
        }
    }

    private fun restoreSurfaces() {
        surfaces.forEach { (view, state) -> state.restore(view) }
    }

    private fun clearPendingPreDrawListeners() {
        pendingPreDrawListeners.toList().forEach {
            removePendingPreDrawListener(it.view, it.observer, it.listener)
        }
    }

    private fun removePendingPreDrawListener(
        view: View,
        observer: ViewTreeObserver,
        listener: ViewTreeObserver.OnPreDrawListener,
    ) {
        pendingPreDrawListeners.removeAll { it.listener === listener }
        val currentObserver = view.viewTreeObserver
        if (currentObserver.isAlive) {
            currentObserver.removeOnPreDrawListener(listener)
        } else if (observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
    }

    private fun Int.sanitizedAlpha(): Int {
        return coerceIn(0, 255)
    }

    private class SurfaceBlurState private constructor(
        private var originalBackground: Drawable?,
        private var originalAlpha: Int,
    ) {
        private var blurDrawable: BackgroundBlurDrawable? = null
        private var layerDrawable: LayerDrawable? = null
        private var rimLayer: LayerDrawable? = null
        private var rimBase: Drawable? = null
        private var rimDrawable: AxBlurRimDrawable? = null
        private val rimPath = Path()
        private val rimRect = RectF()
        private var surfaceBackground: Drawable? = originalBackground?.mutate()
        private val outline = Outline()
        private var tintOverridden = false
        private var originalBlurAlpha = 255
        private var originalBlurRadius = 0
        private var originalUseDrawableAlphaForTint = false

        fun apply(
            view: View, context: Context, radius: Int, alpha: Int,
            customization: AxBlurCustomization.State?,
            rim: AxBlurRim,
        ) {
            captureExternalBackground(view)
            val overrideTint = customization?.tintEnabled == true
            if (radius <= 0 && !overrideTint) {
                restore(view)
                (surfaceBackground as? BackgroundBlurDrawable)?.setBlurRadius(0)
                applyRim(view, rim, alpha)
                return
            }
            val existingBlur = surfaceBackground as? BackgroundBlurDrawable
            if (existingBlur != null) {
                if (!tintOverridden && overrideTint) {
                    originalBlurAlpha = existingBlur.alpha
                    originalBlurRadius = existingBlur.blurRadius
                    originalUseDrawableAlphaForTint = existingBlur.isUsingDrawableAlphaForTint
                }
                existingBlur.setTintOverride(
                    overrideTint,
                    if (overrideTint) customization!!.resolveTint(context, existingBlur.originalColor) else 0,
                )
                existingBlur.setUseDrawableAlphaForTint(
                    if (overrideTint) true else if (tintOverridden) originalUseDrawableAlphaForTint
                    else existingBlur.isUsingDrawableAlphaForTint,
                )
                tintOverridden = overrideTint
                existingBlur.setVisible(true, false)
                existingBlur.setBlurRadius(radius)
                if (!overrideTint) existingBlur.setAlpha(255)
                view.background = existingBlur
                applyRim(view, rim, alpha)
                return
            }
            val blur = blurDrawable ?: view.viewRootImpl?.createBackgroundBlurDrawable()?.also {
                blurDrawable = it
            } ?: run {
                restore(view)
                applyRim(view, rim, alpha)
                return
            }
            blur.setVisible(true, false)
            if (!overrideTint || view.background !== blur) blur.setAlpha(255)
            blur.setBlurRadius(radius)
            blur.setUseDrawableAlphaForTint(overrideTint)
            blur.setColor(if (surfaceBackground == null) AxBlurColors.tint(context) else Color.TRANSPARENT)
            blur.setTintOverride(
                overrideTint,
                if (overrideTint) customization!!.resolveTint(context, Color.TRANSPARENT) else 0,
            )
            applyCorners(blur)
            val overlay = surfaceBackground
            if (overlay == null || overrideTint) {
                surfaceBackground.applyAlpha(originalAlpha)
                view.background = blur
            } else {
                overlay.applyAlpha(alpha)
                view.background = layerDrawable ?: LayerDrawable(arrayOf(blur, overlay)).also {
                    layerDrawable = it
                }
            }
            applyRim(view, rim, alpha)
        }

        private fun applyRim(view: View, rim: AxBlurRim, alpha: Int) {
            val base = if (view.background === rimLayer) rimBase else view.background
            if (!rim.enabled) {
                if (view.background === rimLayer && rimLayer != null) view.background = base
                rimLayer = null
                rimBase = null
                rimDrawable = null
                return
            }
            val highlight = rimDrawable ?: AxBlurRimDrawable(view.resources.displayMetrics.density, rim).also {
                rimDrawable = it
            }
            highlight.rim = rim
            highlight.alpha = originalAlpha
            val background = resolveSurfaceDrawable(surfaceBackground) ?: surfaceBackground
            if (background is GradientDrawable) {
                if (background.shape == GradientDrawable.OVAL) {
                    rimRect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    rimPath.rewind()
                    rimPath.addOval(rimRect, Path.Direction.CW)
                    highlight.setCornerRadii(null)
                    highlight.setClipPath(rimPath)
                    highlight.cornerRadiusPx = 0f
                } else {
                    highlight.setClipPath(null)
                    highlight.setCornerRadii(background.cornerRadii)
                    highlight.cornerRadiusPx = background.cornerRadius
                }
            } else {
                highlight.setCornerRadii(null)
                outline.setEmpty()
                background?.getOutline(outline)
                highlight.setClipPath(if (outline.mMode == Outline.MODE_PATH) outline.mPath else null)
                highlight.cornerRadiusPx = outline.radius.takeIf { it >= 0f } ?: 0f
            }
            if (rimLayer == null || rimBase !== base) {
                rimBase = base
                rimLayer = LayerDrawable(if (base == null) arrayOf(highlight) else arrayOf(base, highlight))
                    .apply { setPaddingMode(LayerDrawable.PADDING_MODE_STACK) }
            }
            view.background = rimLayer
        }

        fun restore(view: View) {
            blurDrawable?.setVisible(false, false)
            blurDrawable?.setBlurRadius(0)
            blurDrawable?.setTintOverride(false, 0)
            if (tintOverridden) {
                (surfaceBackground as? BackgroundBlurDrawable)?.let {
                    it.setTintOverride(false, 0)
                    it.setAlpha(originalBlurAlpha)
                    it.setBlurRadius(originalBlurRadius)
                    it.setUseDrawableAlphaForTint(originalUseDrawableAlphaForTint)
                }
                tintOverridden = false
            }
            surfaceBackground.applyAlpha(originalAlpha)
            val background = view.background
            if ((layerDrawable != null && background === layerDrawable) ||
                (blurDrawable != null && background === blurDrawable) ||
                (rimLayer != null && background === rimLayer)
            ) {
                view.background = surfaceBackground
            }
            layerDrawable = null
            rimLayer = null
            rimBase = null
            rimDrawable = null
        }

        fun hasExternalBackground(view: View): Boolean {
            return !ownsBackground(view.background)
        }

        private fun ownsBackground(background: Drawable?): Boolean {
            return background === surfaceBackground ||
                (layerDrawable != null && background === layerDrawable) ||
                (blurDrawable != null && background === blurDrawable) ||
                (rimLayer != null && background === rimLayer)
        }

        private fun captureExternalBackground(view: View) {
            val background = view.background
            if (!ownsBackground(background)) {
                if (tintOverridden) {
                    (surfaceBackground as? BackgroundBlurDrawable)?.let {
                        it.setTintOverride(false, 0)
                        it.setAlpha(originalBlurAlpha)
                            it.setBlurRadius(originalBlurRadius)
                        it.setUseDrawableAlphaForTint(originalUseDrawableAlphaForTint)
                    }
                    tintOverridden = false
                }
                originalBackground = background
                originalAlpha = background?.alpha ?: 255
                surfaceBackground = background?.mutate()
                layerDrawable = null
                rimLayer = null
                rimBase = null
                rimDrawable = null
            }
        }

        private fun resolveSurfaceDrawable(drawable: Drawable?): Drawable? {
            if (drawable == null || drawable is BackgroundBlurDrawable) return null
            if (drawable.current !== drawable) {
                return resolveSurfaceDrawable(drawable.current)
            }
            if (drawable is DrawableWrapper) {
                return resolveSurfaceDrawable(drawable.drawable)
            }
            if (drawable is LayerDrawable) {
                for (i in 0 until drawable.numberOfLayers) {
                    val resolved = resolveSurfaceDrawable(drawable.getDrawable(i))
                    if (resolved != null) return resolved
                }
                return null
            }
            return drawable
        }

        private fun applyCorners(blur: BackgroundBlurDrawable) {
            val background = resolveSurfaceDrawable(surfaceBackground) ?: surfaceBackground
            if (background is GradientDrawable) {
                val radii = background.cornerRadii
                if (radii != null && radii.size >= 8) {
                    blur.setCornerRadius(
                        radii.cornerRadiusAt(0),
                        radii.cornerRadiusAt(2),
                        radii.cornerRadiusAt(6),
                        radii.cornerRadiusAt(4),
                    )
                    return
                }
                blur.setCornerRadius(background.cornerRadius)
                return
            }
            if (background != null) {
                outline.setEmpty()
                background.getOutline(outline)
                val radius = outline.radius.takeIf { it >= 0f } ?: 0f
                blur.setCornerRadius(radius)
                return
            }
            blur.setCornerRadius(0f)
        }

        private fun Drawable?.applyAlpha(alpha: Int) {
            this?.alpha = alpha
        }

        private fun FloatArray.cornerRadiusAt(index: Int): Float {
            return (this[index] + this[index + 1]) * 0.5f
        }

        companion object {
            fun capture(view: View): SurfaceBlurState {
                val background = view.background
                return SurfaceBlurState(background, background?.alpha ?: 255)
            }
        }
    }

    companion object {
        private const val DEFAULT_BLUR_BEHIND_RADIUS_SCALE = 0.55f
        private const val DEFAULT_SURFACE_ALPHA = AX_BLUR_SURFACE_ALPHA

        @JvmStatic
        fun supportsBlur(): Boolean {
            return AxBlurSupport.supportsCrossWindowBlur()
        }

        @JvmStatic
        @JvmOverloads
        fun applyBlurBehind(window: Window?, context: Context? = null) {
            apply(window, context, true, 1f)
        }

        @JvmStatic
        @JvmOverloads
        fun apply(
            window: Window?,
            context: Context? = null,
            blurBehind: Boolean = false,
            blurBehindRadiusScale: Float = DEFAULT_BLUR_BEHIND_RADIUS_SCALE,
            surfaceAlpha: Int = DEFAULT_SURFACE_ALPHA,
        ) {
            if (window == null) return
            val decor = window.decorView
            val existing = decor.getTag(R.id.ax_window_blur_applied_controller) as? AxWindowBlurController
            val controller = if (existing != null && existing.blurBehind == blurBehind &&
                existing.blurBehindRadiusScale == blurBehindRadiusScale && existing.surfaceAlpha == surfaceAlpha
            ) {
                existing
            } else {
                existing?.detach()
                AxWindowBlurController(
                    window, context, blurBehind, blurBehindRadiusScale, surfaceAlpha,
                ).also { controller ->
                    decor.setTag(R.id.ax_window_blur_applied_controller, controller)
                    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            if (view.getTag(R.id.ax_window_blur_applied_controller) === controller) {
                                controller.start()
                            }
                        }

                        override fun onViewDetachedFromWindow(view: View) {
                            controller.detach()
                            if (view.getTag(R.id.ax_window_blur_applied_controller) === controller) {
                                view.setTag(R.id.ax_window_blur_applied_controller, null)
                            }
                            view.removeOnAttachStateChangeListener(this)
                        }
                    })
                }
            }
            if (decor.isAttachedToWindow) controller.start() else controller.apply()
        }

        @JvmStatic
        @JvmOverloads
        fun attach(
            dialog: Dialog,
            blurBehind: Boolean = false,
            blurBehindRadiusScale: Float = DEFAULT_BLUR_BEHIND_RADIUS_SCALE,
            surfaceAlpha: Int = DEFAULT_SURFACE_ALPHA,
        ): AxWindowBlurController {
            val window = dialog.window!!
            val controller = AxWindowBlurController(
                window,
                dialog.context,
                blurBehind,
                blurBehindRadiusScale,
                surfaceAlpha,
                AxBackdropBlurSettingsSpec.dialog(),
            )
            val decorView = window.decorView
            decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    controller.start()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    controller.detach()
                    view.removeOnAttachStateChangeListener(this)
                }
            })
            if (decorView.isAttachedToWindow) {
                controller.start()
            }
            return controller
        }
    }

    private data class PendingPreDraw(
        val view: View,
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnPreDrawListener,
    )
}
