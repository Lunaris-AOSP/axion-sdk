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
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import com.android.axion.blur.domain.interactor.AxBackdropBlurInteractor
import com.android.axion.blur.model.AxBackdropBlurSettingsModel
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
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

    fun start() {
        if (!started) {
            settingsObserver.start()
            started = true
        }
        apply()
    }

    fun stop() {
        if (started) {
            settingsObserver.stop()
            started = false
        }
        clearPendingPreDrawListeners()
        clearWindowBlur()
        restoreSurfaces()
    }

    fun apply() {
        apply(blurInteractor.settings())
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
        val radius = settings.blurRadiusPx.roundToInt()
        val supported = AxBlurSupport.supportsCrossWindowBlur() && !AxBlurProperties.disableBlur
        val supportedRadius = if (supported) {
            radius
        } else {
            0
        }
        val advancedBlurEnabled = settings.advancedBlurEnabled && supported
        val alpha = if (supportedRadius > 0 || advancedBlurEnabled) surfaceAlpha.sanitizedAlpha() else 255
        applyWindowBlur(supportedRadius)
        applySurfaceBlur(supportedRadius, alpha, advancedBlurEnabled)
    }

    private fun applyWindowBlur(radius: Int) {
        val backgroundRadius = if (blurBehind) radius.coerceAtLeast(0) else 0
        setWindowBackgroundBlurRadius(backgroundRadius)
        val behindRadius =
            (backgroundRadius * blurBehindRadiusScale).roundToInt().coerceAtLeast(0)
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

    private fun applySurfaceBlur(radius: Int, alpha: Int, advancedBlurEnabled: Boolean) {
        surfaces.forEach { (view, state) ->
            state.apply(view, context, radius, alpha, advancedBlurEnabled)
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
        private var glassDrawable: Drawable? = null
        private var surfaceBackground: Drawable? = originalBackground?.mutate()
        private data class ExistingBlurState(
            val radius: Int,
            val glassMode: Int,
            val density: Float,
            val visible: Boolean,
            val shader: String?,
            val shaderTint: Int,
        )
        private var originalBlurState = captureBlurState(surfaceBackground)
        private val outline = Outline()

        fun apply(view: View, context: Context, radius: Int, alpha: Int, advancedBlurEnabled: Boolean) {
            captureExternalBackground(view)
            if (radius <= 0 && !advancedBlurEnabled) {
                restore(view)
                return
            }
            val existingBlur = surfaceBackground as? BackgroundBlurDrawable
            if (existingBlur != null) {
                existingBlur.setVisible(true, false)
                existingBlur.setBlurRadius(radius)
                AxAdvancedBlur.apply(existingBlur, context, advancedBlurEnabled)
                existingBlur.setAlpha(255)
                view.background = existingBlur
                return
            }
            val blur = blurDrawable ?: view.viewRootImpl?.createBackgroundBlurDrawable()?.also {
                blurDrawable = it
            } ?: run {
                restore(view)
                return
            }
            blur.setVisible(true, false)
            blur.setApplyRenderNodeAlpha(true)
            blur.setAlpha(255)
            blur.setBlurRadius(radius)
            AxAdvancedBlur.apply(blur, context, advancedBlurEnabled)
            blur.setColor(
                if (surfaceBackground == null) {
                    AxBlurColors.tint(context)
                } else {
                    Color.TRANSPARENT
                },
            )
            applyCorners(blur)
            val overlay = surfaceBackground.takeUnless { advancedBlurEnabled }
            if (overlay == null) {
                surfaceBackground.applyAlpha(originalAlpha)
                view.background = if (surfaceBackground == null) blur else {
                    glassDrawable ?: GlassSurfaceDrawable(blur, surfaceBackground!!).also {
                        glassDrawable = it
                    }
                }
            } else {
                overlay.applyAlpha(alpha)
                view.background = layerDrawable ?: object : LayerDrawable(arrayOf(blur, overlay)) {
                    private val fallback = overlay.constantState?.newDrawable(view.resources)
                        ?.mutate()?.apply { this.alpha = originalAlpha } ?: overlay
                    override fun draw(canvas: android.graphics.Canvas) {
                        if (canvas.isHardwareAccelerated) super.draw(canvas)
                        else {
                            fallback.bounds = bounds
                            fallback.state = overlay.state
                            fallback.level = overlay.level
                            fallback.draw(canvas)
                        }
                    }
                }.also {
                    layerDrawable = it
                }
            }
        }

        fun restore(view: View) {
            blurDrawable?.setVisible(false, false)
            blurDrawable?.setBlurRadius(0)
            blurDrawable?.setGlassBlur(BackgroundBlurDrawable.GLASS_MODE_NONE, 1f)
            val existingBlur = surfaceBackground as? BackgroundBlurDrawable
            originalBlurState?.let { state ->
                existingBlur?.setBlurRadius(state.radius)
                if (state.shader != null) {
                    existingBlur?.setAdvancedBlurShader(state.shader, state.density, state.shaderTint,
                        state.glassMode == 4)
                } else {
                    existingBlur?.setGlassBlur(state.glassMode, state.density)
                }
                existingBlur?.setVisible(state.visible, false)
            }
            surfaceBackground.applyAlpha(originalAlpha)
            if (view.background === layerDrawable || view.background === blurDrawable
                || view.background === glassDrawable) {
                view.background = surfaceBackground
            }
            layerDrawable = null
            glassDrawable = null
            blurDrawable?.callback = null
            blurDrawable = null
        }

        private fun captureExternalBackground(view: View) {
            val background = view.background
            if (
                background !== layerDrawable &&
                background !== glassDrawable &&
                background !== blurDrawable &&
                background !== surfaceBackground
            ) {
                originalBackground = background
                originalAlpha = background?.alpha ?: 255
                surfaceBackground = background?.mutate()
                originalBlurState = captureBlurState(surfaceBackground)
                layerDrawable = null
                glassDrawable = null
            }
        }

        private fun captureBlurState(background: Drawable?): ExistingBlurState? {
            val blur = background as? BackgroundBlurDrawable ?: return null
            return ExistingBlurState(
                blur.getBlurRadius(), blur.getGlassMode(), blur.getGlassDensity(), blur.isVisible, blur.getAdvancedBlurShader(),
                blur.getAdvancedBlurTint(),
            )
        }

        private fun applyCorners(blur: BackgroundBlurDrawable) {
            val background = surfaceBackground
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

        private class GlassSurfaceDrawable(
            private val blur: BackgroundBlurDrawable,
            background: Drawable,
        ) : DrawableWrapper(background) {
            init { blur.callback = this }

            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

            override fun draw(canvas: android.graphics.Canvas) {
                if (!canvas.isHardwareAccelerated) {
                    super.draw(canvas)
                    return
                }
                blur.callback = this
                blur.bounds = bounds
                blur.draw(canvas)
                drawable?.let { drawOverlays(canvas, it, findFill(it)) }
            }

            private fun findFill(background: Drawable): Drawable? {
                val current = background.current
                if (current !== background) return findFill(current)
                if (background is DrawableWrapper) return background.drawable?.let(::findFill)
                if (background is LayerDrawable) {
                    for (index in 0 until background.numberOfLayers) {
                        if (background.getId(index) == android.R.id.mask) continue
                        findFill(background.getDrawable(index))?.let { return it }
                    }
                    return null
                }
                return background.takeIf { it is GradientDrawable || it is ColorDrawable }
            }

            private fun drawOverlays(canvas: android.graphics.Canvas, background: Drawable,
                fill: Drawable?) {
                if (background === fill) return
                val current = background.current
                if (current !== background) {
                    drawOverlays(canvas, current, fill)
                } else if (background is DrawableWrapper) {
                    background.drawable?.let { drawOverlays(canvas, it, fill) }
                } else if (background is LayerDrawable) {
                    for (index in 0 until background.numberOfLayers) {
                        if (background.getId(index) != android.R.id.mask) {
                            drawOverlays(canvas, background.getDrawable(index), fill)
                        }
                    }
                    if (background is RippleDrawable) background.drawRippleOnly(canvas)
                } else {
                    background.draw(canvas)
                }
            }
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
            AxWindowBlurController(
                window,
                context,
                blurBehind,
                blurBehindRadiusScale,
                surfaceAlpha,
            ).apply()
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
