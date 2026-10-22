/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.policy;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.view.CrossWindowBlurListeners;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

import java.util.function.Consumer;

/** @hide */
public final class AxDialogBlurController {
    private static final String BLUR_RADIUS = "system_blur_radius";
    private static final float MAX_BLUR_RADIUS_DP = 34f;
    private static final int SURFACE_ALPHA = 0x8A;

    private final Window mWindow;
    private final ViewGroup mDecor;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final WindowManager mWindowManager;
    private final ArrayMap<View, BlurBackground> mBackgrounds = new ArrayMap<>();
    private final ContentObserver mSettingsObserver;
    private final Consumer<Boolean> mBlurListener;
    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        updateBackgrounds();
        return true;
    };
    private boolean mStarted;
    private boolean mCrossWindowBlurEnabled;
    private int mRadius;

    public static AxDialogBlurController create(Window window, ViewGroup decor, boolean isDialog) {
        final ApplicationInfo app = window.getContext().getApplicationInfo();
        if ((!app.isSystemApp() && !app.isUpdatedSystemApp())
                || (!isDialog && !window.isFloating()
                        && !(window.getCallback() instanceof AlertActivity))
                || !CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED
                || !ActivityManager.isHighEndGfx()
                || SystemProperties.getBoolean("persist.sysui.disableBlur", false)) {
            return null;
        }
        return new AxDialogBlurController(window, decor);
    }

    private AxDialogBlurController(Window window, ViewGroup decor) {
        mWindow = window;
        mDecor = decor;
        mResolver = window.getContext().getContentResolver();
        mHandler = new Handler(Looper.myLooper());
        mWindowManager = window.getWindowManager();
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateSettings();
            }
        };
        mBlurListener = enabled -> {
            mCrossWindowBlurEnabled = enabled;
            updateSettings();
        };
    }

    public void prepare() {
        mCrossWindowBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        readSettings();
        if (mRadius > 0) {
            addBackgrounds(mDecor);
        }
    }

    public void start() {
        if (mStarted) return;
        mStarted = true;
        mCrossWindowBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
        mResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DISABLE_WINDOW_BLURS), false,
                mSettingsObserver);
        mResolver.registerContentObserver(Settings.Secure.getUriFor(BLUR_RADIUS), false,
                mSettingsObserver);
        mWindowManager.addCrossWindowBlurEnabledListener(mHandler::post, mBlurListener);
        mDecor.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        updateSettings();
    }

    public void stop() {
        if (!mStarted) return;
        updateBackgrounds();
        mStarted = false;
        mRadius = 0;
        mResolver.unregisterContentObserver(mSettingsObserver);
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        if (mDecor.getViewTreeObserver().isAlive()) {
            mDecor.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
        }
        for (int i = mBackgrounds.size() - 1; i >= 0; i--) {
            restoreBackground(i);
        }
    }

    private void updateSettings() {
        if (!mStarted) return;
        readSettings();
        for (int i = 0; i < mBackgrounds.size(); i++) {
            mBackgrounds.valueAt(i).updateRadius();
            mBackgrounds.keyAt(i).invalidate();
        }
        mDecor.invalidate();
    }

    private void readSettings() {
        final Context context = mWindow.getContext();
        final int defaultDisabled =
                SystemProperties.getBoolean("ro.custom.blur.enable", true) ? 0 : 1;
        final boolean enabled = mCrossWindowBlurEnabled
                && Settings.Global.getInt(mResolver, Settings.Global.DISABLE_WINDOW_BLURS,
                        defaultDisabled) == 0;
        final float maxRadius = MAX_BLUR_RADIUS_DP
                * context.getResources().getDisplayMetrics().density;
        final float radius = Settings.Secure.getFloat(mResolver, BLUR_RADIUS, maxRadius);
        mRadius = enabled ? Math.round(Float.isFinite(radius)
                ? Math.max(0f, Math.min(radius, maxRadius)) : maxRadius) : 0;
    }

    private void updateBackgrounds() {
        if (!mStarted) return;
        for (int i = mBackgrounds.size() - 1; i >= 0; i--) {
            final View view = mBackgrounds.keyAt(i);
            if (findBlurBackground(view.getBackground()) != mBackgrounds.valueAt(i)
                    || view.getViewRootImpl() != mDecor.getViewRootImpl()
                    || Boolean.TRUE.equals(view.getTag(R.id.ax_dialog_blur_owned))) {
                restoreBackground(i);
            }
        }
        addBackgrounds(mDecor);
    }

    private void addBackgrounds(ViewGroup view) {
        if (Boolean.TRUE.equals(view.getTag(R.id.ax_dialog_blur_owned))) return;
        Drawable background = view.getBackground();
        final BlurBackground wrapper = findBlurBackground(background);
        if (wrapper != null) {
            if (wrapper.owner() == this) {
                mBackgrounds.put(view, wrapper);
                return;
            }
            wrapper.clearBlur();
            background = removeBlurBackground(background, wrapper);
            view.setBackground(background);
        }
        final boolean fullScreenDecor = view == mDecor
                && mWindow.getAttributes().width == ViewGroup.LayoutParams.MATCH_PARENT
                && mWindow.getAttributes().height == ViewGroup.LayoutParams.MATCH_PARENT;
        if (!fullScreenDecor && background != null
                && background.getOpacity() != PixelFormat.TRANSPARENT) {
            if (mRadius > 0 && surfaceDrawable(background) != null) {
                final BlurBackground replacement = new BlurBackground(background);
                mBackgrounds.put(view, replacement);
                view.setBackground(replacement);
            }
            return;
        }
        for (int i = 0; i < view.getChildCount(); i++) {
            final View child = view.getChildAt(i);
            if (child instanceof ViewGroup) {
                addBackgrounds((ViewGroup) child);
            }
        }
    }

    private void restoreBackground(int index) {
        final View view = mBackgrounds.keyAt(index);
        final BlurBackground wrapper = mBackgrounds.valueAt(index);
        wrapper.clearBlur();
        final Drawable background = view.getBackground();
        view.setBackground(removeBlurBackground(background, wrapper));
        view.invalidate();
        mBackgrounds.removeAt(index);
    }

    private static BlurBackground findBlurBackground(Drawable background) {
        if (background instanceof BlurBackground) return (BlurBackground) background;
        if (background instanceof DrawableWrapper) {
            return findBlurBackground(((DrawableWrapper) background).getDrawable());
        }
        if (background instanceof LayerDrawable) {
            final LayerDrawable layers = (LayerDrawable) background;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                final BlurBackground wrapper = findBlurBackground(layers.getDrawable(i));
                if (wrapper != null) return wrapper;
            }
        }
        return null;
    }

    private static Drawable removeBlurBackground(Drawable background, BlurBackground target) {
        if (background == target) return target.getDrawable();
        if (background instanceof DrawableWrapper) {
            final DrawableWrapper wrapper = (DrawableWrapper) background;
            final Drawable child = wrapper.getDrawable();
            final Drawable replacement = removeBlurBackground(child, target);
            if (replacement != child) {
                final Rect bounds = new Rect(child.getBounds());
                wrapper.setDrawable(replacement);
                replacement.setBounds(bounds);
            }
        } else if (background instanceof LayerDrawable) {
            final LayerDrawable layers = (LayerDrawable) background;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                final Drawable child = layers.getDrawable(i);
                final Drawable replacement = removeBlurBackground(child, target);
                if (replacement != child) {
                    layers.setDrawable(i, replacement);
                }
            }
        }
        return background;
    }

    private static Drawable surfaceDrawable(Drawable drawable) {
        if (drawable == null || drawable instanceof BackgroundBlurDrawable) return null;
        if (drawable.getCurrent() != drawable) {
            return surfaceDrawable(drawable.getCurrent());
        }
        if (drawable instanceof InsetDrawable) {
            return surfaceDrawable(((InsetDrawable) drawable).getDrawable());
        }
        if (drawable instanceof LayerDrawable) {
            final LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (layers.getDrawable(i) instanceof BackgroundBlurDrawable) return null;
            }
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                final Drawable surface = surfaceDrawable(layers.getDrawable(i));
                if (surface != null && surface.getOpacity() != PixelFormat.TRANSPARENT) {
                    return surface;
                }
            }
            return null;
        }
        if (drawable instanceof ColorDrawable) return drawable;
        if (drawable instanceof GradientDrawable) {
            final GradientDrawable gradient = (GradientDrawable) drawable;
            return gradient.getShape() == GradientDrawable.RECTANGLE ? gradient : null;
        }
        return null;
    }

    private final class BlurBackground extends InsetDrawable {
        private final Rect mBlurBounds = new Rect();
        private BackgroundBlurDrawable mBlur;

        BlurBackground(Drawable original) {
            super(original, 0);
        }

        AxDialogBlurController owner() {
            return AxDialogBlurController.this;
        }

        @Override
        public void draw(Canvas canvas) {
            if (mRadius <= 0 || !canvas.isHardwareAccelerated()
                    || mDecor.getViewRootImpl() == null) {
                clearBlur();
                super.draw(canvas);
                return;
            }
            if (!isVisible() || getAlpha() == 0) return;
            final Drawable surface = surfaceDrawable(getDrawable());
            if (surface == null || surface.getBounds().isEmpty()) {
                clearBlur();
                super.draw(canvas);
                return;
            }
            if (mBlur == null) {
                mBlur = mDecor.getViewRootImpl().createBackgroundBlurDrawable();
                mBlur.setApplyRenderNodeAlpha(true);
                mBlur.setColor(Color.TRANSPARENT);
            }
            mBlurBounds.set(surface.getBounds());
            mBlur.setBounds(mBlurBounds);
            mBlur.setBlurRadius(mRadius);
            final float windowAlpha = mWindow.getAttributes().alpha;
            mBlur.setAlpha(Float.isFinite(windowAlpha)
                    ? Math.round(getAlpha() * Math.max(0f, Math.min(1f, windowAlpha))) : 0);
            if (surface instanceof GradientDrawable) {
                final GradientDrawable gradient = (GradientDrawable) surface;
                final float[] radii = gradient.getCornerRadii();
                if (radii != null && radii.length >= 8) {
                    mBlur.setCornerRadius((radii[0] + radii[1]) * 0.5f,
                            (radii[2] + radii[3]) * 0.5f,
                            (radii[6] + radii[7]) * 0.5f,
                            (radii[4] + radii[5]) * 0.5f);
                } else {
                    mBlur.setCornerRadius(gradient.getCornerRadius());
                }
            } else {
                mBlur.setCornerRadius(0f);
            }
            mBlur.draw(canvas);
            final Rect bounds = getBounds();
            final int save = canvas.saveLayerAlpha(bounds.left, bounds.top,
                    bounds.right, bounds.bottom, SURFACE_ALPHA);
            super.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public ConstantState getConstantState() {
            return null;
        }

        void updateRadius() {
            if (mBlur != null) {
                mBlur.setBlurRadius(mRadius);
            }
        }

        void clearBlur() {
            if (mBlur != null) {
                mBlur.setBlurRadius(0);
                mBlur = null;
            }
        }
    }
}
