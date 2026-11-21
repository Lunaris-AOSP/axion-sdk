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

package com.android.axion.blur.ui.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.ArraySet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.android.axion.blur.AxBlurSupport
import com.android.axion.blur.AxAdvancedBlur
import com.android.axion.blur.AxBlurColors
import com.android.axion.blur.AxBlurForeground
import com.android.internal.graphics.AdvancedBlurConfig
import com.android.axion.blur.AxAdvancedBlurEffect
import com.android.axion.blur.AxWindowBlurSource
import com.android.axion.blur.domain.interactor.AxBackdropBlurInteractor
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec
import com.android.axion.blur.model.AxBackdropBlurSettingsSubscription
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import kotlin.math.max
import kotlin.math.roundToInt

class AxViewBackdropBlur @JvmOverloads constructor(
    private val view: View,
    private val observeSettings: Boolean = true,
) {
    internal var onInvalidate: (() -> Unit)? = null

    private val path = Path()
    private val rect = RectF()
    private val childRect = RectF()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targetRect = Rect()
    private val targetRectF = RectF()
    private val transformMatrix = Matrix()
    private val scaledCornerRadii = FloatArray(8)
    private val sourceBlurNode = RenderNode("AxViewBackdropBlur")
    private class SourceAdvancedBlurEntry(view: View) {
        val node = RenderNode("AxSourceAdvancedBlur").apply { setClipToBounds(false) }
        val effect = AxAdvancedBlurEffect(view.context)
        val bounds = Rect()
        val transform = Matrix()
        var snapshot = false
    }
    private val sourceAdvancedBlurEntries = LinkedHashMap<Any, SourceAdvancedBlurEntry>()
    private val sourceAdvancedBlurTransform = Matrix()
    private val sourceCaptureBounds = RectF()
    private val sourceCaptureRect = Rect()
    private var advancedBlurEnabled = false
    private var advancedBlurPreset: AdvancedBlurConfig? = null
    private var tintForeground = false
    private val foreground by lazy { AxBlurForeground(view) }
    private var backdropMaterial = false
    private var lastAdvancedBlurRadiusPx = -1f
    private var lastAdvancedBlurConfig: String? = null
    private var lastAdvancedBlurPreset: AdvancedBlurConfig? = null
    private val defaultKey = Any()
    private val drawables = LinkedHashMap<Any, BackgroundBlurDrawable>()
    private val drawableAlphaStates = LinkedHashMap<Any, DrawableAlphaState>()
    private val resolvedDrawableAlphas = LinkedHashMap<Any, Int>()
    private val trackedStates = LinkedHashMap<View, ViewFrameState>()
    private val excludedSourceViews = ArrayList<View>()
    private val excludedSourceBranches = ArrayList<View>()
    private val sourceContentViews = ArraySet<View>()
    private var sourceDrawStopBranch: View? = null
    private var settingsInteractor = AxBackdropBlurInteractor(view.context)
    private var settingsSubscription: AxBackdropBlurSettingsSubscription? = null
    private var observingPreDraw = false
    private var observingDraw = false
    private var observingWindowFocus = false
    private var attached = false
    private var visibilityAggregated = true
    private var enabled = false
    private var globalBlurEnabled = true
    private var useSettingsBlurRadius = true
    private var blurRadiusPx = 0f
    private var alpha = 255
    private var overlayColor = Color.TRANSPARENT
    private var sourceView: View? = null
    private var windowSourceView: View? = null
    private var windowSourceRoot: View? = null
    private val sourceToLocalTransform = Matrix()
    private var sourceLocalWidth = 0
    private var sourceLocalHeight = 0
    private val windowSourceListener = AxWindowBlurSource.Listener { source ->
        windowSourceView?.let { if (it !== view && it !== sourceView) trackedStates.remove(it) }
        windowSourceView = source
        if (sourceView == null) {
            clearCrossWindowBlur()
            discardSourceBlur()
            source?.let(::trackView)
            invalidateHost()
        }
    }
    private var preferSourceBlur = false
    private var requireSourceBlur = false
    private var forceSourceBlurUpdate = false
    private var sourceBlurUpdateSuppressed = false
    private var crossWindowBlurEnabled = true
    private var requireWindowFocus = false
    private var sourceBlurRecorded = false
    private var sourceBlurDirty = true
    private var sourceBlurSnapshot = false
    private val recordedSourceState = SourceRecord()
    private var sourceBlurEffect: RenderEffect? = null
    private var sourceBlurEffectRadius = -1f
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (attached && requireWindowFocus && enabled && globalBlurEnabled && visibilityAggregated) {
            if (!focused) {
                clear()
            } else {
                updatePreDrawObserver()
                invalidateHost()
            }
        }
    }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (enabled) {
            val stateChanged = hasTrackedStateChanged()
            val alphaChanged = updateDrawableAlphas()
            if (stateChanged || alphaChanged) {
                invalidateHost()
            }
        }
        true
    }
    private val drawListener = ViewTreeObserver.OnDrawListener {
        if (enabled) {
            updateDrawableAlphas()
        }
    }

    init {
        view.setTagInternal(com.android.internal.R.id.ax_dialog_blur_owned, true)
        applyBlurSettings()
    }

    fun isEnabled(): Boolean {
        return enabled
    }

    /** Whether the next successful draw supplies its own material tint and highlights. */
    fun isAdvancedBlurActive(): Boolean = advancedBlurEnabled && isCrossWindowBlurActive()

    fun setTintForeground(enabled: Boolean) {
        tintForeground = enabled
        if (enabled) foreground.update(isAdvancedBlurActive()) else foreground.restore()
    }

    fun drawForegroundFallback(canvas: Canvas, background: Drawable) {
        if (tintForeground) foreground.drawFallback(canvas, background) else background.draw(canvas)
    }

    fun getForegroundFallbackColor(): Int =
        if (tintForeground) foreground.fallbackForegroundColor else Color.TRANSPARENT

    internal fun resolveFallbackColor(color: Int): Int {
        val preset = advancedBlurPreset.takeIf { enabled && globalBlurEnabled && advancedBlurEnabled }
            ?: return color
        if (Color.alpha(color) == 0) return color
        fun light(value: Int) = maxOf(Color.red(value), Color.green(value), Color.blue(value)) +
            minOf(Color.red(value), Color.green(value), Color.blue(value)) >= 255
        val lightForeground = light(preset.contentColor(view.context))
        val defaultForeground = view.context.getColor(com.android.internal.R.color.materialColorOnSurface)
        if (lightForeground == light(defaultForeground)) {
            return color
        }
        val background = view.context.getColor(if (lightForeground) android.R.color.system_neutral1_900
            else android.R.color.system_neutral1_50)
        return (color and 0xff000000.toInt()) or (background and 0x00ffffff)
    }

    fun isCrossWindowBlurActive(): Boolean {
        return globalBlurEnabled &&
            hasRequiredWindowFocus() &&
            shouldTrackFrames() &&
            ((sourceView == null && windowSourceView != null) ||
                (crossWindowBlurEnabled && AxBlurSupport.supportsCrossWindowBlur()))
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            clear()
        } else {
            updatePreDrawObserver()
        }
        updateSettingsObserver()
        if (enabled) {
            applyBlurSettings()
        }
        invalidateHost()
    }

    fun setAlpha(alpha: Int) {
        val coerced = alpha.coerceIn(0, 255)
        if (this.alpha == coerced) return
        val previous = this.alpha
        this.alpha = coerced
        drawableAlphaStates.entries.forEach { entry ->
            if (entry.value.alpha == previous) {
                entry.setValue(entry.value.copy(alpha = coerced))
            }
        }
        updateDrawableAlphas()
        invalidateHost()
    }

    fun setSurfaceAlpha(alpha: Int) {
        setAlpha(alpha)
    }

    fun setAdvancedBlurEnabled(enabled: Boolean) {
        if (advancedBlurEnabled == enabled) return
        advancedBlurEnabled = enabled
        lastAdvancedBlurRadiusPx = if (enabled) AxAdvancedBlur.radiusPx(view.context, backdropMaterial) else -1f
        if (!enabled) {
            if (tintForeground) foreground.restore()
            lastAdvancedBlurConfig = null
            lastAdvancedBlurPreset = null
        }
        invalidateSourceBlurEffect()
        invalidateHost()
    }

    fun setBackdropMaterial(backdrop: Boolean) {
        if (backdropMaterial == backdrop) return
        backdropMaterial = backdrop
        lastAdvancedBlurConfig = null
        invalidateSourceBlurEffect()
        applyBlurSettings()
        invalidateHost()
    }

    internal fun setSettingsEnabled(enabled: Boolean) {
        if (globalBlurEnabled == enabled) return
        globalBlurEnabled = enabled
        if (enabled) {
            updatePreDrawObserver()
        } else {
            clear()
        }
        invalidateHost()
    }

    internal fun updateAdvancedBlurRadius() {
        if (!advancedBlurEnabled) return
        val radius = AxAdvancedBlur.radiusPx(view.context, backdropMaterial)
        val preset = AdvancedBlurConfig.current(view.context)
        val config = preset?.shaderSource(view.context, AxBlurColors.materialTint(view.context), backdropMaterial)
        if (lastAdvancedBlurRadiusPx == radius && lastAdvancedBlurConfig == config
            && lastAdvancedBlurPreset === preset) return
        lastAdvancedBlurRadiusPx = radius
        lastAdvancedBlurConfig = config
        lastAdvancedBlurPreset = preset
        invalidateSourceBlurEffect()
        invalidateHost()
    }

    fun setOverlayColor(color: Int) {
        if (overlayColor == color) return
        overlayColor = color
        invalidateHost()
    }

    fun setBlurRadiusPx(radius: Float) {
        useSettingsBlurRadius = false
        updateBlurRadiusPx(radius)
        updateSettingsObserver()
    }

    fun setSourceView(source: View?) {
        if (sourceView === source) return
        val previous = sourceView
        sourceView = source
        if (previous != null && previous !== view) {
            trackedStates.remove(previous)
        }
        if (source != null) {
            trackView(source)
        }
        discardSourceBlur()
        updatePreDrawObserver()
        invalidateHost()
    }

    /** Maps host-view coordinates into the local canvas of a Compose surface. */
    fun setSourceToLocalTransform(transform: Matrix, width: Int, height: Int) {
        if (sourceToLocalTransform == transform &&
            sourceLocalWidth == width && sourceLocalHeight == height) return
        sourceToLocalTransform.set(transform)
        sourceLocalWidth = width.coerceAtLeast(0)
        sourceLocalHeight = height.coerceAtLeast(0)
        sourceBlurDirty = true
        invalidateHost()
    }

    private fun resolvedSourceView(): View? = sourceView ?: windowSourceView

    fun setExcludedSourceViews(vararg excludedViews: View?) {
        var filteredCount = 0
        excludedViews.forEach { if (it != null) filteredCount++ }
        var changed = excludedSourceViews.size != filteredCount
        if (!changed) {
            var index = 0
            excludedViews.forEach { excludedView ->
                if (excludedView != null && excludedSourceViews[index++] !== excludedView) {
                    changed = true
                    return@forEach
                }
            }
        }
        if (!changed) return
        excludedSourceViews.forEach { trackedStates.remove(it) }
        excludedSourceViews.clear()
        excludedViews.forEach { excludedView ->
            if (excludedView != null && !excludedSourceViews.contains(excludedView)) {
                excludedSourceViews.add(excludedView)
                trackView(excludedView)
            }
        }
        discardSourceBlur()
        updatePreDrawObserver()
        invalidateHost()
    }

    fun setPreferSourceBlur(prefer: Boolean) {
        if (preferSourceBlur == prefer) return
        preferSourceBlur = prefer
        invalidateHost()
    }

    fun setRequireSourceBlur(require: Boolean) {
        if (requireSourceBlur == require) return
        requireSourceBlur = require
        invalidateHost()
    }

    fun setForceSourceBlurUpdate(force: Boolean) {
        if (forceSourceBlurUpdate == force) return
        forceSourceBlurUpdate = force
        sourceBlurDirty = true
        invalidateHost()
    }

    fun setSourceBlurUpdateSuppressed(suppressed: Boolean) {
        if (sourceBlurUpdateSuppressed == suppressed) return
        val wasSuppressed = sourceBlurUpdateSuppressed
        sourceBlurUpdateSuppressed = suppressed
        if (wasSuppressed && !suppressed) {
            sourceBlurDirty = true
        }
        invalidateHost()
    }

    fun setCrossWindowBlurEnabled(enabled: Boolean) {
        if (crossWindowBlurEnabled == enabled) return
        crossWindowBlurEnabled = enabled
        if (!enabled) {
            clearCrossWindowBlur()
        }
        invalidateHost()
    }

    fun setRequireWindowFocus(require: Boolean) {
        if (requireWindowFocus == require) return
        requireWindowFocus = require
        updateWindowFocusObserver()
        if (!hasRequiredWindowFocus()) clear()
        invalidateHost()
    }

    fun captureSourceBlur(source: View, vararg excludedViews: View?): Boolean {
        setSourceView(source)
        setExcludedSourceViews(*excludedViews)
        setPreferSourceBlur(true)
        setSourceBlurUpdateSuppressed(false)
        val captured = recordSourceSnapshot(source)
        setSourceBlurUpdateSuppressed(captured)
        invalidateHost()
        return captured
    }

    fun releaseSourceBlur() {
        setSourceBlurUpdateSuppressed(false)
        setPreferSourceBlur(false)
        setSourceView(null)
        setExcludedSourceViews()
        discardSourceBlur()
        invalidateHost()
    }

    fun useSystemBlurRadius() {
        useSettings(AxBackdropBlurSettingsSpec.system())
    }

    fun useSettings(settingsSpec: AxBackdropBlurSettingsSpec) {
        useSettingsBlurRadius = true
        settingsInteractor = AxBackdropBlurInteractor(view.context, settingsSpec)
        applyBlurSettings()
        resetSettingsObserver()
    }

    fun onAttachedToWindow() {
        attached = true
        visibilityAggregated = view.isShown
        val root = view.rootView
        windowSourceRoot?.let { AxWindowBlurSource.removeListener(it, windowSourceListener) }
        windowSourceRoot = root
        AxWindowBlurSource.addListener(root, windowSourceListener)
        applyBlurSettings()
        updateSettingsObserver()
        updatePreDrawObserver()
        updateWindowFocusObserver()
    }

    fun onDetachedFromWindow() {
        if (tintForeground) foreground.restore()
        attached = false
        updateWindowFocusObserver()
        windowSourceRoot?.let { AxWindowBlurSource.removeListener(it, windowSourceListener) }
        windowSourceRoot = null
        windowSourceView = null
        stopSettingsObserver()
        sourceView = null
        excludedSourceViews.clear()
        clear()
    }

    fun onVisibilityAggregated(isVisible: Boolean) {
        visibilityAggregated = isVisible
        if (!isVisible) {
            clear()
        } else {
            applyBlurSettings()
            updatePreDrawObserver()
            invalidateHost()
        }
        updateSettingsObserver()
    }

    fun verifyDrawable(who: Drawable): Boolean {
        return drawables.values.any { it === who }
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        target: View,
        alpha: Int = this.alpha,
    ): Boolean {
        trackView(target)
        if (target.width <= 0 || target.height <= 0) {
            clearKey(target)
            return false
        }
        val bounds = targetBounds(target)
        val scale = targetCornerScale(target, bounds)
        val background = target.background
        val targetAlphaSource = if (target === view) null else target
        if (background is GradientDrawable) {
            val radii = background.cornerRadii
            if (radii != null && radii.size >= 8) {
                val drawRadii = scaledCornerRadii(radii, scale)
                return drawInternal(
                    canvas,
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    drawRadii,
                    drawRadii.maxCornerRadius(),
                    alpha,
                    target,
                    targetAlphaSource = targetAlphaSource,
                )
            }
            return drawInternal(
                canvas,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                null,
                background.cornerRadius * scale,
                alpha,
                target,
                targetAlphaSource = targetAlphaSource,
            )
        }
        return drawInternal(
            canvas,
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            null,
            0f,
            alpha,
            target,
            targetAlphaSource = targetAlphaSource,
        )
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        bounds: Rect?,
        cornerRadii: FloatArray,
        alpha: Int = this.alpha,
    ): Boolean {
        if (bounds == null) return false
        return draw(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom, cornerRadii, alpha)
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        bounds: RectF?,
        clipPath: Path?,
        cornerRadius: Float,
        alpha: Int = this.alpha,
    ): Boolean {
        if (bounds == null) return false
        bounds.roundOut(targetRect)
        return drawInternal(
            canvas,
            targetRect.left,
            targetRect.top,
            targetRect.right,
            targetRect.bottom,
            null,
            cornerRadius,
            alpha,
            defaultKey,
            clipPath,
        )
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray,
        alpha: Int = this.alpha,
    ): Boolean {
        val cornerRadius = if (cornerRadii.size >= 8) {
            cornerRadii.maxCornerRadius()
        } else {
            0f
        }
        return drawInternal(canvas, left, top, right, bottom, cornerRadii, cornerRadius, alpha)
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadius: Float,
        alpha: Int = this.alpha,
    ): Boolean {
        return drawInternal(canvas, left, top, right, bottom, null, cornerRadius, alpha)
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        key: Any,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadius: Float,
        alpha: Int = this.alpha,
    ): Boolean {
        return drawInternal(canvas, left, top, right, bottom, null, cornerRadius, alpha, key)
    }

    @JvmOverloads
    fun draw(
        canvas: Canvas,
        key: Any,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray,
        alpha: Int = this.alpha,
    ): Boolean {
        val cornerRadius = if (cornerRadii.size >= 8) {
            cornerRadii.maxCornerRadius()
        } else {
            0f
        }
        return drawInternal(canvas, left, top, right, bottom, cornerRadii, cornerRadius, alpha, key)
    }

    private fun drawInternal(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray?,
        cornerRadius: Float,
        alpha: Int,
        key: Any = defaultKey,
        clipPath: Path? = null,
        applyViewAlpha: Boolean = true,
        targetAlphaSource: View? = null,
    ): Boolean {
        // Software previews must not deactivate a compositor region still used on screen.
        if (!canvas.isHardwareAccelerated) return false
        trackView(view)
        if (targetAlphaSource != null) {
            trackView(targetAlphaSource)
        }
        val requestedAlpha = alpha.coerceIn(0, 255)
        val previousAlphaState = drawableAlphaStates[key]
        val alphaState = if (previousAlphaState != null &&
            previousAlphaState.alpha == requestedAlpha &&
            previousAlphaState.applyViewAlpha == applyViewAlpha &&
            previousAlphaState.target === targetAlphaSource
        ) {
            previousAlphaState
        } else {
            DrawableAlphaState(requestedAlpha, applyViewAlpha, targetAlphaSource).also {
                drawableAlphaStates[key] = it
            }
        }
        val crossWindowAlpha = resolvedCrossWindowAlpha(alphaState)
        if (tintForeground) foreground.update(isAdvancedBlurActive() && crossWindowAlpha > 0)
        if (crossWindowAlpha <= 0) {
            clearKey(key)
            return true
        }
        if (!canDrawGeometry(canvas, left, top, right, bottom)) {
            clearKey(key)
            return false
        }
        if (sourceView == null && windowSourceView != null) {
            clearKey(key, clearSource = false)
            val sourceAlpha = if (targetAlphaSource == null) requestedAlpha else {
                scaledDrawableAlpha(requestedAlpha, targetLocalAlpha(targetAlphaSource) ?: 0f)
            }
            val drewSource = drawSourceBlur(
                canvas,
                left,
                top,
                right,
                bottom,
                cornerRadii,
                cornerRadius,
                sourceAlpha,
                clipPath,
                key = key,
            )
            if (!drewSource) clearKey(key)
            return drewSource
        }
        if (advancedBlurEnabled && sourceView != null) {
            val drewSource = drawSourceBlur(
                canvas, left, top, right, bottom, cornerRadii, cornerRadius,
                resolvedDrawableAlpha(alphaState), clipPath, key = key,
            )
            if (drewSource || requireSourceBlur) {
                clearKey(key, clearSource = !drewSource)
                return drewSource
            }
        }
        if (preferSourceBlur) {
            val drewCrossWindow = drawCrossWindowBlur(
                canvas,
                left,
                top,
                right,
                bottom,
                cornerRadii,
                cornerRadius,
                crossWindowAlpha,
                key,
                clipPath,
            )
            if (drewCrossWindow && shouldUseCrossWindowBlur()) {
                if (sourceBlurRecorded) discardSourceBlur()
                return true
            }
            val drewSource = drawSourceBlur(
                canvas,
                left,
                top,
                right,
                bottom,
                cornerRadii,
                cornerRadius,
                resolvedDrawableAlpha(alphaState),
                clipPath,
                drawOverlay = !drewCrossWindow,
                key = key,
            )
            if (!drewCrossWindow) clearKey(key, clearSource = !drewSource)
            if (!drewSource && requireSourceBlur) {
                if (drewCrossWindow) clearKey(key)
                return false
            }
            if (drewSource) return true
            if (drewCrossWindow) {
                return true
            }
            return false
        }
        if (drawCrossWindowBlur(
                canvas,
                left,
                top,
                right,
                bottom,
                cornerRadii,
                cornerRadius,
                crossWindowAlpha,
                key,
                clipPath,
            )
        ) {
            return true
        }
        clearKey(key, clearSource = false)
        val drewSource = drawSourceBlur(
            canvas,
            left,
            top,
            right,
            bottom,
            cornerRadii,
            cornerRadius,
            resolvedDrawableAlpha(alphaState),
            clipPath,
            key = key,
        )
        if (!drewSource) clearKey(key)
        return drewSource
    }

    private fun drawCrossWindowBlur(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray?,
        cornerRadius: Float,
        alpha: Int,
        key: Any,
        clipPath: Path?,
        color: Int = overlayColor,
    ): Boolean {
        if (!crossWindowBlurEnabled) return false
        if (!AxBlurSupport.supportsCrossWindowBlur()) return false
        val blurDrawable = blurDrawableFor(key) ?: return false
        blurDrawable.setApplyRenderNodeAlpha(true)
        blurDrawable.setVisible(true, false)
        blurDrawable.setBlurRadius(
            (if (advancedBlurEnabled) AxAdvancedBlur.radiusPx(view.context, backdropMaterial) else blurRadiusPx).roundToInt(),
        )
        AxAdvancedBlur.apply(blurDrawable, view.context, advancedBlurEnabled, backdropMaterial)
        blurDrawable.alpha = alpha
        resolvedDrawableAlphas[key] = alpha
        blurDrawable.setColor(color)
        applyCornerRadius(blurDrawable, cornerRadii, cornerRadius)
        setBoundsIfChanged(blurDrawable, left, top, right, bottom)
        val save = canvas.save()
        clip(canvas, left, top, right, bottom, cornerRadii, cornerRadius, clipPath)
        blurDrawable.draw(canvas)
        canvas.restoreToCount(save)
        return true
    }

    private fun drawSourceBlur(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray?,
        cornerRadius: Float,
        alpha: Int,
        clipPath: Path?,
        drawOverlay: Boolean = true,
        key: Any = defaultKey,
    ): Boolean {
        val source = resolvedSourceView() ?: return false
        if (
            source === view ||
            view.width <= 0 ||
            view.height <= 0 ||
            source.width <= 0 ||
            source.height <= 0
        ) {
            return false
        }
        updateAdvancedBlurRadius()
        if (shouldRecordSource(source) && !recordSource(source)) return false
        val sharp = advancedBlurEnabled && !backdropMaterial
            && AdvancedBlurConfig.current(view.context)?.usesSharpContent() == true
        sourceBlurNode.setRenderEffect(if (sharp) null else resolveSourceBlurEffect())
        sourceBlurNode.alpha = if (sourceView == null) source.visualAlpha() else 1f
        val save = if (alpha < 255) {
            canvas.saveLayerAlpha(
                left.toFloat(),
                top.toFloat(),
                right.toFloat(),
                bottom.toFloat(),
                alpha,
            )
        } else {
            canvas.save()
        }
        clip(canvas, left, top, right, bottom, cornerRadii, cornerRadius, clipPath)
        if (advancedBlurEnabled) {
            val entry = sourceAdvancedBlurEntries.getOrPut(key) { SourceAdvancedBlurEntry(view) }
            sourceAdvancedBlurTransform.reset()
            if (sourceBlurSnapshot) {
                sourceAdvancedBlurTransform.set(sourceToLocalTransform)
                updateTransformToView(source)
                sourceAdvancedBlurTransform.preConcat(transformMatrix)
            }
            if (!entry.node.hasDisplayList() || entry.bounds.left != left || entry.bounds.top != top
                || entry.bounds.right != right || entry.bounds.bottom != bottom
                || entry.snapshot != sourceBlurSnapshot || entry.transform != sourceAdvancedBlurTransform) {
                entry.bounds.set(left, top, right, bottom)
                entry.transform.set(sourceAdvancedBlurTransform)
                entry.snapshot = sourceBlurSnapshot
                entry.node.setPosition(left, top, right, bottom)
                val recording = entry.node.beginRecording(right - left, bottom - top)
                recording.translate(-left.toFloat(), -top.toFloat())
                if (sourceBlurSnapshot) recording.concat(sourceAdvancedBlurTransform)
                recording.drawRenderNode(sourceBlurNode)
                entry.node.endRecording()
            }
            entry.node.setRenderEffect(entry.effect.resolve(
                if (sharp) AxAdvancedBlur.radiusPx(view.context, backdropMaterial) else 0f,
                0f, 0f, (right - left).toFloat(), (bottom - top).toFloat(),
                cornerRadius, cornerRadii, backdropMaterial,
            ))
            canvas.drawRenderNode(entry.node)
        } else {
            drawSourceBlurNode(canvas, source)
        }
        if (drawOverlay && !advancedBlurEnabled) {
            drawOverlay(canvas, left, top, right, bottom, cornerRadii, cornerRadius, clipPath)
        }
        canvas.restoreToCount(save)
        return true
    }

    private fun drawSourceBlurNode(canvas: Canvas, source: View) {
        val save = canvas.save()
        if (sourceBlurSnapshot) {
            canvas.concat(sourceToLocalTransform)
            updateTransformToView(source)
            canvas.concat(transformMatrix)
        }
        canvas.drawRenderNode(sourceBlurNode)
        canvas.restoreToCount(save)
    }

    private fun shouldRecordSource(source: View): Boolean {
        if (sourceBlurUpdateSuppressed && sourceBlurRecorded) return false
        if (forceSourceBlurUpdate) return true
        return sourceBlurDirty ||
            !sourceBlurRecorded ||
            !recordedSourceState.matches(view, source)
    }

    private fun recordSource(source: View): Boolean {
        recordedSourceState.update(view, source)
        trackView(source)
        clearSourceContentViews()
        sourceBlurSnapshot = false
        val outset = sourceOutset()
        val width = sourceLocalWidth.takeIf { it > 0 } ?: view.width
        val height = sourceLocalHeight.takeIf { it > 0 } ?: view.height
        updateTransformToView(source)
        sourceAdvancedBlurTransform.set(sourceToLocalTransform)
        sourceAdvancedBlurTransform.preConcat(transformMatrix)
        sourceCaptureBounds.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
        sourceAdvancedBlurTransform.mapRect(sourceCaptureBounds)
        if (!sourceCaptureBounds.intersect(-outset.toFloat(), -outset.toFloat(),
                width + outset.toFloat(), height + outset.toFloat())) {
            discardSourceBlur()
            return false
        }
        sourceCaptureBounds.roundOut(sourceCaptureRect)
        if (sourceCaptureRect.isEmpty) {
            discardSourceBlur()
            return false
        }
        sourceBlurNode.setPosition(sourceCaptureRect.left, sourceCaptureRect.top,
            sourceCaptureRect.right, sourceCaptureRect.bottom)
        val recordingCanvas = sourceBlurNode.beginRecording(sourceCaptureRect.width(), sourceCaptureRect.height())
        val save = recordingCanvas.save()
        recordingCanvas.translate(-sourceCaptureRect.left.toFloat(), -sourceCaptureRect.top.toFloat())
        recordingCanvas.concat(sourceAdvancedBlurTransform)
        val recorded = drawSource(recordingCanvas, source)
        recordingCanvas.restoreToCount(save)
        sourceBlurNode.endRecording()
        sourceBlurDirty = !recorded
        sourceBlurRecorded = recorded
        if (!recorded) {
            sourceBlurNode.discardDisplayList()
        }
        return recorded
    }

    private fun recordSourceSnapshot(source: View): Boolean {
        if (!shouldTrackFrames() || source.width <= 0 || source.height <= 0) {
            discardSourceBlur()
            return false
        }
        recordedSourceState.update(view, source)
        trackView(source)
        clearSourceContentViews()
        sourceBlurNode.setPosition(0, 0, source.width, source.height)
        val recordingCanvas = sourceBlurNode.beginRecording(source.width, source.height)
        val recorded = drawSource(recordingCanvas, source)
        sourceBlurNode.endRecording()
        sourceBlurDirty = !recorded
        sourceBlurRecorded = recorded
        sourceBlurSnapshot = recorded
        if (!recorded) {
            sourceBlurNode.discardDisplayList()
        }
        return recorded
    }

    private fun shouldUseCrossWindowBlur(): Boolean {
        val root = view.rootView
        val rootArea = areaOf(root.width, root.height)
        if (rootArea <= 0) return false
        val outset = sourceOutset()
        return areaOf(view.width + outset * 2, view.height + outset * 2) >= rootArea / 2
    }

    private fun areaOf(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        return width.toLong() * height.toLong()
    }

    private fun sourceOutset(): Int {
        val preset = if (advancedBlurEnabled) AdvancedBlurConfig.current(view.context) else null
        val radius = if (preset != null) {
            (if (backdropMaterial) preset.backdropBlurDp else preset.blurDp) *
                view.resources.displayMetrics.density
        } else blurRadiusPx
        return AxAdvancedBlur.sourceOutset(view.context, radius, advancedBlurEnabled, backdropMaterial)
    }

    private fun drawSource(canvas: Canvas, source: View): Boolean {
        if (source is ViewGroup) {
            collectExcludedSourceBranches(source)
            if (excludedSourceBranches.isNotEmpty() || sourceDrawStopBranch != null) {
                drawViewGroupWithoutTargets(canvas, source, excludedSourceBranches)
                return true
            }
        }
        source.draw(canvas)
        return true
    }

    private fun collectExcludedSourceBranches(group: ViewGroup) {
        excludedSourceBranches.clear()
        sourceDrawStopBranch = findSourceBranch(group, view)
        if (sourceDrawStopBranch == null) {
            addExcludedSourceBranch(group, view)
        }
        excludedSourceViews.forEach { addExcludedSourceBranch(group, it) }
    }

    private fun addExcludedSourceBranch(group: ViewGroup, target: View) {
        val branch = findSourceBranch(group, target) ?: return
        if (!excludedSourceBranches.contains(branch)) {
            excludedSourceBranches.add(branch)
        }
    }

    private fun findSourceBranch(group: ViewGroup, target: View): View? {
        var current = target
        while (current.parent is View) {
            val parent = current.parent as View
            if (parent === group) {
                return current
            }
            current = parent
        }
        return null
    }

    private fun drawViewGroupWithoutTargets(
        canvas: Canvas,
        group: ViewGroup,
        targetBranches: List<View>,
    ) {
        drawViewBackground(canvas, group)
        val save = canvas.save()
        canvas.translate(-group.scrollX.toFloat(), -group.scrollY.toFloat())
        if (group.clipToPadding) {
            canvas.clipRect(
                group.scrollX + group.paddingLeft,
                group.scrollY + group.paddingTop,
                group.scrollX + group.width - group.paddingRight,
                group.scrollY + group.height - group.paddingBottom,
            )
        }
        val stopIndex = sourceDrawStopBranch?.let { group.indexOfChild(it) } ?: group.childCount
        val childCount = if (stopIndex >= 0) stopIndex else group.childCount
        for (i in 0 until childCount) {
            val child = group.getChildAt(i)
            if (!targetBranches.contains(child)) {
                drawChild(canvas, child)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawViewBackground(canvas: Canvas, source: View) {
        val background = source.background ?: return
        val save = canvas.save()
        canvas.translate(source.scrollX.toFloat(), source.scrollY.toFloat())
        background.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawChild(canvas: Canvas, child: View) {
        trackSourceContentView(child)
        val childAlpha = child.visualAlpha()
        if ((child.visibility != View.VISIBLE && child.animation == null) || childAlpha <= 0f) return
        if (drawChildRenderNode(canvas, child)) return
        drawChildFallback(canvas, child, childAlpha)
    }

    private fun drawChildRenderNode(canvas: Canvas, child: View): Boolean {
        if (!canvas.isHardwareAccelerated || !child.canHaveDisplayList()) return false
        val renderNode = child.updateDisplayListIfDirty()
        if (!renderNode.hasDisplayList()) return false
        canvas.drawRenderNode(renderNode)
        return true
    }

    private fun drawChildFallback(
        canvas: Canvas,
        child: View,
        childAlpha: Float,
    ) {
        val left = child.left.toFloat()
        val top = child.top.toFloat()
        val matrix = child.matrix
        val save = if (childAlpha < 1f) {
            childRect.set(0f, 0f, child.width.toFloat(), child.height.toFloat())
            if (!matrix.isIdentity) {
                matrix.mapRect(childRect)
            }
            childRect.offset(left, top)
            canvas.saveLayerAlpha(
                childRect.left,
                childRect.top,
                childRect.right,
                childRect.bottom,
                (childAlpha * 255).roundToInt().coerceIn(0, 255),
            )
        } else {
            canvas.save()
        }
        canvas.translate(left, top)
        if (!matrix.isIdentity) {
            canvas.concat(matrix)
        }
        child.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun resolveSourceBlurEffect(): RenderEffect? {
        val radius = if (advancedBlurEnabled) AxAdvancedBlur.radiusPx(view.context, backdropMaterial) else blurRadiusPx
        if (radius <= 0f) return null
        val cached = sourceBlurEffect
        if (cached != null && sourceBlurEffectRadius == radius) {
            return cached
        }
        return RenderEffect.createBlurEffect(
            radius,
            radius,
            Shader.TileMode.CLAMP,
        ).also {
            sourceBlurEffect = it
            sourceBlurEffectRadius = radius
        }
    }

    private fun targetBounds(target: View): Rect {
        targetRectF.set(0f, 0f, target.width.toFloat(), target.height.toFloat())
        if (target !== view) {
            updateTransformToView(target)
            transformMatrix.mapRect(targetRectF)
        }
        targetRectF.roundOut(targetRect)
        return targetRect
    }

    private fun targetCornerScale(target: View, bounds: Rect): Float {
        if (target === view || target.width <= 0 || target.height <= 0) return 1f
        val scaleX = bounds.width().toFloat() / target.width
        val scaleY = bounds.height().toFloat() / target.height
        val scale = max(scaleX, scaleY)
        return if (scale.isFinite() && scale > 0f) scale else 1f
    }

    private fun scaledCornerRadii(radii: FloatArray, scale: Float): FloatArray {
        if (scale == 1f) return radii
        for (index in 0 until 8) {
            scaledCornerRadii[index] = radii[index] * scale
        }
        return scaledCornerRadii
    }

    private fun updateTransformToView(source: View) {
        transformMatrix.reset()
        source.transformMatrixToGlobal(transformMatrix)
        view.transformMatrixToLocal(transformMatrix)
    }

    private fun canDrawGeometry(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        return enabled && globalBlurEnabled && visibilityAggregated &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (advancedBlurEnabled || blurRadiusPx.roundToInt() > 0) &&
            canvas.isHardwareAccelerated &&
            hasRequiredWindowFocus() &&
            left < right &&
            top < bottom
    }

    private fun hasRequiredWindowFocus(): Boolean {
        return !requireWindowFocus || view.hasWindowFocus()
    }

    private fun viewTreeAlpha(): Float? {
        if (!view.getGlobalVisibleRect(targetRect)) return null
        var alpha = 1f
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return null
            alpha *= current.visualAlpha()
            current = current.parent as? View
        }
        return (alpha * windowAlpha()).sanitizedAlphaFactor()
    }

    private fun resolvedDrawableAlpha(state: DrawableAlphaState): Int {
        var drawAlpha = state.alpha.coerceIn(0, 255)
        if (drawAlpha <= 0) return 0
        val target = state.target
        if (target != null) {
            drawAlpha = scaledDrawableAlpha(drawAlpha, targetLocalAlpha(target) ?: return 0)
        }
        if (state.applyViewAlpha) {
            drawAlpha = scaledDrawableAlpha(drawAlpha, viewTreeAlpha() ?: return 0)
        }
        return drawAlpha
    }

    private fun resolvedCrossWindowAlpha(state: DrawableAlphaState): Int {
        var drawAlpha = state.alpha.coerceIn(0, 255)
        val target = state.target
        if (target != null) {
            drawAlpha = scaledDrawableAlpha(drawAlpha, targetLocalAlpha(target) ?: return 0)
        }
        return scaledDrawableAlpha(drawAlpha, windowAlpha())
    }

    private fun updateDrawableAlphas(): Boolean {
        var changed = false
        val iterator = drawables.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = drawableAlphaStates[entry.key]
            if (state == null) {
                deactivateCrossWindowBlurDrawable(entry.value)
                resolvedDrawableAlphas.remove(entry.key)
                iterator.remove()
                changed = true
                continue
            }
            val alpha = resolvedCrossWindowAlpha(state)
            if (alpha <= 0) {
                deactivateCrossWindowBlurDrawable(entry.value)
                drawableAlphaStates.remove(entry.key)
                resolvedDrawableAlphas.remove(entry.key)
                iterator.remove()
                changed = true
                continue
            }
            if (resolvedDrawableAlphas[entry.key] != alpha) {
                entry.value.alpha = alpha
                resolvedDrawableAlphas[entry.key] = alpha
                changed = true
            }
        }
        return changed
    }

    private fun targetLocalAlpha(target: View): Float? {
        if (!target.getGlobalVisibleRect(targetRect)) return null
        var alpha = 1f
        var current: View? = target
        while (current != null) {
            if (isHostAncestor(current)) return alpha.sanitizedAlphaFactor()
            if (current.visibility != View.VISIBLE) return null
            alpha *= current.visualAlpha()
            current = current.parent as? View
        }
        return alpha.sanitizedAlphaFactor()
    }

    private fun isHostAncestor(target: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === target) return true
            current = current.parent as? View
        }
        return false
    }

    private fun View.visualAlpha(): Float {
        val value = alpha * transitionAlpha
        return value.sanitizedAlphaFactor()
    }

    private fun windowAlpha(): Float {
        val value = view.viewRootImpl?.mWindowAttributes?.alpha ?: 1f
        return value.sanitizedAlphaFactor()
    }

    private fun Float.sanitizedAlphaFactor(): Float {
        return if (isFinite()) coerceIn(0f, 1f) else 0f
    }

    private fun scaledDrawableAlpha(alpha: Int, factor: Float): Int {
        val sourceAlpha = alpha.coerceIn(0, 255)
        if (sourceAlpha <= 0) return 0
        return (sourceAlpha * factor.sanitizedAlphaFactor())
            .roundToInt()
            .coerceIn(0, 255)
    }

    private fun trackView(target: View) {
        if (!shouldTrackFrames()) return
        trackedStates.getOrPut(target) { ViewFrameState() }
            .update(target, transformMatrix, targetRectF, targetRect, false)
        updatePreDrawObserver()
    }

    private fun trackSourceContentView(target: View) {
        sourceContentViews.add(target)
        trackView(target)
    }

    private fun shouldTrackFrames(): Boolean {
        return enabled && globalBlurEnabled && visibilityAggregated &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (advancedBlurEnabled || blurRadiusPx.roundToInt() > 0)
    }

    private fun hasTrackedStateChanged(): Boolean {
        var changed = false
        val iterator = trackedStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = entry.key
            if (target !== view && !target.isAttachedToWindow) {
                if (target === resolvedSourceView() || sourceContentViews.remove(target)) {
                    sourceBlurDirty = true
                }
                clearKey(target)
                iterator.remove()
                changed = true
            } else {
                val sourceContentChanged = sourceContentViews.contains(target)
                val isSource = target === resolvedSourceView()
                val affectsSource = target === view || isSource || sourceContentChanged
                val trackSourceDirty = isSource || sourceContentChanged
                val stateChanged = entry.value.update(
                    target,
                    transformMatrix,
                    targetRectF,
                    targetRect,
                    trackSourceDirty,
                )
                if (stateChanged && affectsSource) {
                    sourceBlurDirty = true
                }
                changed = changed || stateChanged
            }
        }
        if (trackedStates.isEmpty()) {
            removePreDrawObserver()
        }
        return changed
    }

    private fun updatePreDrawObserver() {
        if (attached && shouldTrackFrames() && trackedStates.isNotEmpty()) {
            addPreDrawObserver()
            addDrawObserver()
        } else {
            removePreDrawObserver()
            removeDrawObserver()
        }
    }

    private fun updateWindowFocusObserver() {
        val observer = view.viewTreeObserver
        if (attached && requireWindowFocus && observer.isAlive) {
            if (!observingWindowFocus) {
                observer.addOnWindowFocusChangeListener(windowFocusListener)
                observingWindowFocus = true
            }
        } else if (observingWindowFocus) {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(windowFocusListener)
            observingWindowFocus = false
        }
    }

    private fun addPreDrawObserver() {
        if (!observingPreDraw) {
            view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            observingPreDraw = true
        }
    }

    private fun removePreDrawObserver() {
        if (observingPreDraw) {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            observingPreDraw = false
        }
    }

    private fun addDrawObserver() {
        if (!observingDraw) {
            view.viewTreeObserver.addOnDrawListener(drawListener)
            observingDraw = true
        }
    }

    private fun removeDrawObserver() {
        if (observingDraw) {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnDrawListener(drawListener)
            }
            observingDraw = false
        }
    }

    private fun blurDrawableFor(key: Any): BackgroundBlurDrawable? {
        return drawables[key] ?: view.viewRootImpl
            ?.createBackgroundBlurDrawable()
            ?.also { drawables[key] = it }
    }

    private fun setBoundsIfChanged(
        drawable: BackgroundBlurDrawable,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val bounds = drawable.bounds
        if (
            bounds.left != left ||
                bounds.top != top ||
                bounds.right != right ||
                bounds.bottom != bottom
        ) {
            drawable.setBounds(left, top, right, bottom)
        }
    }

    private fun applyCornerRadius(
        blurDrawable: BackgroundBlurDrawable,
        cornerRadii: FloatArray?,
        cornerRadius: Float,
    ) {
        if (cornerRadii != null && cornerRadii.size >= 8) {
            blurDrawable.setCornerRadius(
                cornerRadii.cornerRadiusAt(0),
                cornerRadii.cornerRadiusAt(2),
                cornerRadii.cornerRadiusAt(6),
                cornerRadii.cornerRadiusAt(4),
            )
            return
        }
        blurDrawable.setCornerRadius(cornerRadius.coerceAtLeast(0f))
    }

    private fun clip(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray?,
        cornerRadius: Float,
        clipPath: Path?,
    ) {
        if (clipPath != null) {
            canvas.clipPath(clipPath)
            return
        }
        rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        path.rewind()
        if (cornerRadii != null && cornerRadii.size >= 8) {
            path.addRoundRect(rect, cornerRadii, Path.Direction.CW)
            canvas.clipPath(path)
        } else if (cornerRadius > 0f) {
            path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(path)
        } else {
            canvas.clipRect(left, top, right, bottom)
        }
    }

    private fun drawOverlay(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerRadii: FloatArray?,
        cornerRadius: Float,
        clipPath: Path?,
        alpha: Int = 255,
    ) {
        if (Color.alpha(overlayColor) == 0) return
        overlayPaint.color = overlayColor
        overlayPaint.alpha = (Color.alpha(overlayColor) * alpha / 255f)
            .roundToInt()
            .coerceIn(0, 255)
        if (clipPath != null) {
            canvas.drawPath(clipPath, overlayPaint)
            return
        }
        rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        path.rewind()
        if (cornerRadii != null && cornerRadii.size >= 8) {
            path.addRoundRect(rect, cornerRadii, Path.Direction.CW)
            canvas.drawPath(path, overlayPaint)
        } else if (cornerRadius > 0f) {
            path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(path, overlayPaint)
        } else {
            canvas.drawRect(rect, overlayPaint)
        }
    }

    private fun applyBlurSettings() {
        val settings = settingsInteractor.settings()
        advancedBlurPreset = settings.advancedBlurConfig.takeIf { settings.advancedBlurEnabled }
        setSettingsEnabled(settings.enabled)
        setAdvancedBlurEnabled(settings.advancedBlurEnabled)
        updateAdvancedBlurRadius()
        if (useSettingsBlurRadius) updateBlurRadiusPx(
            if (settings.advancedBlurEnabled && backdropMaterial)
                AxAdvancedBlur.radiusPx(view.context, true) else settings.blurRadiusPx,
        )
    }

    private fun updateBlurRadiusPx(radius: Float) {
        val coerced = if (radius.isFinite()) radius.coerceAtLeast(0f) else 0f
        if (blurRadiusPx == coerced) return
        blurRadiusPx = coerced
        invalidateSourceBlurEffect()
        if (coerced == 0f) {
            clear()
            return
        }
        invalidateHost()
    }

    private fun updateSettingsObserver() {
        if (observeSettings && attached && enabled && visibilityAggregated) {
            startSettingsObserver()
        } else {
            stopSettingsObserver()
        }
    }

    private fun resetSettingsObserver() {
        settingsSubscription?.stop()
        settingsSubscription = null
        updateSettingsObserver()
    }

    private fun startSettingsObserver() {
        val observer = settingsSubscription ?: settingsInteractor.createSubscription {
            applyBlurSettings()
        }.also {
            settingsSubscription = it
        }
        observer.start()
    }

    private fun stopSettingsObserver() {
        settingsSubscription?.stop()
    }

    fun clear() {
        if (tintForeground) foreground.restore()
        lastAdvancedBlurConfig = null
        lastAdvancedBlurPreset = null
        if (sourceBlurEffect != null) {
            sourceBlurEffect = null
            sourceBlurNode.setRenderEffect(null)
        }
        val hadContent = drawables.isNotEmpty() || trackedStates.isNotEmpty()
            || sourceBlurRecorded || sourceBlurNode.hasDisplayList()
            || sourceAdvancedBlurEntries.isNotEmpty()
            || drawableAlphaStates.isNotEmpty() || resolvedDrawableAlphas.isNotEmpty()
        if (!hadContent) {
            updatePreDrawObserver()
            return
        }
        clearCrossWindowBlur()
        trackedStates.clear()
        discardSourceBlur()
        updatePreDrawObserver()
        invalidateHost()
    }

    fun clearCrossWindowBlur() {
        if (drawables.isEmpty() && drawableAlphaStates.isEmpty() && resolvedDrawableAlphas.isEmpty()) return
        drawables.values.forEach(::deactivateCrossWindowBlurDrawable)
        drawables.clear()
        drawableAlphaStates.clear()
        resolvedDrawableAlphas.clear()
        updatePreDrawObserver()
        invalidateHost()
    }

    fun refreshSourceBlur() {
        discardSourceBlur()
        invalidateHost()
    }

    fun clear(target: View?) {
        if (target != null) {
            clearKey(target)
            if (target === resolvedSourceView() || sourceContentViews.remove(target)) {
                discardSourceBlur()
            }
            trackedStates.remove(target)
            updatePreDrawObserver()
            invalidateHost()
        }
    }

    private fun invalidateHost() {
        onInvalidate?.invoke()
        view.postInvalidateOnAnimation()
        val root = view.rootView
        if (root !== view) {
            root.postInvalidateOnAnimation()
        }
    }

    private fun clearKey(key: Any, clearSource: Boolean = true) {
        drawableAlphaStates.remove(key)
        resolvedDrawableAlphas.remove(key)
        drawables.remove(key)?.let(::deactivateCrossWindowBlurDrawable)
        if (clearSource) sourceAdvancedBlurEntries.remove(key)?.node?.discardDisplayList()
    }

    private fun deactivateCrossWindowBlurDrawable(drawable: BackgroundBlurDrawable) {
        drawable.alpha = 0
        drawable.setColor(Color.TRANSPARENT)
        drawable.setBounds(0, 0, 0, 0)
        drawable.setVisible(false, false)
        drawable.setBlurRadius(0)
    }

    private fun invalidateSourceBlurEffect() {
        sourceBlurEffect = null
        if (sourceBlurSnapshot && sourceBlurRecorded) {
            sourceAdvancedBlurEntries.values.forEach { it.node.discardDisplayList() }
            sourceAdvancedBlurEntries.clear()
        } else {
            discardSourceBlur()
        }
    }

    private fun discardSourceBlur() {
        sourceAdvancedBlurEntries.values.forEach { it.node.discardDisplayList() }
        sourceAdvancedBlurEntries.clear()
        clearSourceContentViews()
        sourceBlurNode.setRenderEffect(null)
        sourceBlurNode.discardDisplayList()
        sourceBlurRecorded = false
        sourceBlurDirty = true
        sourceBlurSnapshot = false
        recordedSourceState.reset()
    }

    private fun clearSourceContentViews() {
        sourceContentViews.forEach { trackedStates.remove(it) }
        sourceContentViews.clear()
    }

    private fun FloatArray.cornerRadiusAt(index: Int): Float {
        return (this[index] + this[index + 1]) * 0.5f
    }

    private fun FloatArray.maxCornerRadius(): Float {
        var radius = 0f
        for (index in 0..6 step 2) {
            radius = max(radius, cornerRadiusAt(index))
        }
        return radius
    }

    private data class DrawableAlphaState(
        val alpha: Int,
        val applyViewAlpha: Boolean,
        val target: View?,
    )

    private class SourceRecord {
        private var viewWidth = -1
        private var viewHeight = -1
        private var sourceWidth = -1
        private var sourceHeight = -1

        fun matches(view: View, source: View): Boolean = viewWidth == view.width
            && viewHeight == view.height && sourceWidth == source.width && sourceHeight == source.height

        fun update(view: View, source: View) {
            viewWidth = view.width
            viewHeight = view.height
            sourceWidth = source.width
            sourceHeight = source.height
        }

        fun reset() {
            viewWidth = -1
            viewHeight = -1
            sourceWidth = -1
            sourceHeight = -1
        }
    }

    private inner class ViewFrameState {
        private val transform = Matrix()
        private val bounds = RectF()
        private val visibleBounds = Rect()
        private val clipBounds = Rect()
        private var initialized = false
        private var width = -1
        private var height = -1
        private var alpha = Float.NaN
        private var scrollX = Int.MIN_VALUE
        private var scrollY = Int.MIN_VALUE
        private var childCount = -1
        private var visible = false
        private var clipped = false

        fun update(
            target: View,
            matrix: Matrix,
            rect: RectF,
            visibleRect: Rect,
            trackDirty: Boolean,
        ): Boolean {
            rect.set(0f, 0f, target.width.toFloat(), target.height.toFloat())
            matrix.reset()
            target.transformMatrixToGlobal(matrix)
            matrix.mapRect(rect)
            val targetVisibleInWindow = target.getGlobalVisibleRect(visibleRect)
            val targetScrollX = target.scrollX
            val targetScrollY = target.scrollY
            val targetChildCount = if (target is ViewGroup) target.childCount else -1
            val targetDirty = trackDirty && target.isDirty
            var changed = !initialized || transform != matrix || bounds != rect
                || visible != targetVisibleInWindow
                || (targetVisibleInWindow && visibleBounds != visibleRect)
            if (targetVisibleInWindow) visibleBounds.set(visibleRect) else visibleBounds.setEmpty()
            val targetClipSet = target.getClipBounds(visibleRect)
            changed = changed || clipped != targetClipSet
                || (targetClipSet && clipBounds != visibleRect)
            if (targetClipSet) clipBounds.set(visibleRect) else clipBounds.setEmpty()
            var treeAlpha = 1f
            var current: View? = target
            while (current != null) {
                if (current.visibility != View.VISIBLE) {
                    treeAlpha = 0f
                    break
                }
                treeAlpha *= current.visualAlpha()
                current = current.parent as? View
            }
            treeAlpha *= windowAlpha()
            changed = changed || width != target.width || height != target.height
                || alpha != treeAlpha || scrollX != targetScrollX || scrollY != targetScrollY
                || childCount != targetChildCount
            transform.set(matrix)
            bounds.set(rect)
            width = target.width
            height = target.height
            alpha = treeAlpha
            scrollX = targetScrollX
            scrollY = targetScrollY
            childCount = targetChildCount
            visible = targetVisibleInWindow
            clipped = targetClipSet
            initialized = true
            return changed || targetDirty
        }
    }
}
