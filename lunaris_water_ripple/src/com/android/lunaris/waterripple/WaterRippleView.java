/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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
package com.android.lunaris.waterripple;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BLASTBufferQueue;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowManagerGlobal;
import android.window.ScreenCaptureInternal;
import android.window.ScreenCaptureInternal.ScreenshotHardwareBuffer;

import java.util.function.ObjIntConsumer;

public final class WaterRippleView extends View {
    public static final int EFFECT_WATER = 1;
    public static final int EFFECT_WATERCOLOR = 2;
    private static final String TAG = "LunarisWaterRipple";
    private static final long SETUP_TIMEOUT_MS = 250;
    private static final long SIMULATION_DURATION_MS = 800;
    private static final int WATERCOLOR_TEXTURE_LIMIT = 1024;
    private static final int WATER_TEXTURE_LIMIT = 2048;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ViewTreeObserver.OnPreDrawListener mPreDraw = this::onPreDraw;
    private Session mSession;
    private Runnable mOnEnd;
    private Runnable mOnFailure;
    private final Runnable mSetupTimeout = () -> finish(false);
    private final Runnable mDeadline = () -> finish(true);
    private float mOriginX;
    private float mOriginY;
    private long mDurationMs;
    private long mStartNs;
    private int mAnimation;
    private boolean mHighFrameRate;
    private boolean mFullscreen;
    private boolean mUnlockEffect;
    private boolean mPlayed;

    public WaterRippleView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void play(float x, float y, long durationMs,
            int animation, boolean highFrameRate, boolean fullscreen,
            Runnable onEnd, Runnable onFailure) {
        playInternal(x, y, durationMs, animation, highFrameRate, fullscreen, false,
                onEnd, onFailure);
    }

    public void playUnlock(float x, float y, long durationMs,
            int animation, boolean highFrameRate, boolean fullscreen,
            Runnable onEnd, Runnable onFailure) {
        playInternal(x, y, durationMs, animation, highFrameRate, fullscreen, true,
                onEnd, onFailure);
    }

    private void playInternal(float x, float y, long durationMs,
            int animation, boolean highFrameRate, boolean fullscreen, boolean unlockEffect,
            Runnable onEnd, Runnable onFailure) {
        if (mPlayed) {
            throw new IllegalStateException("Use a new WaterRippleView for each animation");
        }
        mPlayed = true;
        mOnEnd = onEnd;
        mOnFailure = onFailure;
        mOriginX = x;
        mOriginY = y;
        mAnimation = animation;
        mHighFrameRate = highFrameRate;
        mFullscreen = fullscreen;
        mUnlockEffect = unlockEffect && fullscreen && animation == EFFECT_WATER;
        mDurationMs = Math.max(1, Math.round(durationMs * ValueAnimator.getDurationScale()));
        mStartNs = System.nanoTime();
        if (!ValueAnimator.areAnimatorsEnabled() || !isHardwareAccelerated()
                || (animation != EFFECT_WATER && animation != EFFECT_WATERCOLOR)) {
            finish(false);
            return;
        }
        mMain.postDelayed(mSetupTimeout, SETUP_TIMEOUT_MS);
        mMain.postDelayed(mDeadline, mDurationMs);
        getViewTreeObserver().addOnPreDrawListener(mPreDraw);
        invalidate();
    }

    public void cancel() {
        mMain.removeCallbacks(mSetupTimeout);
        mMain.removeCallbacks(mDeadline);
        getViewTreeObserver().removeOnPreDrawListener(mPreDraw);
        mOnEnd = null;
        mOnFailure = null;
        if (mSession != null) mSession.stop();
    }

    private void finish(boolean success) {
        Runnable callback = success ? mOnEnd : mOnFailure;
        cancel();
        if (callback != null) callback.run();
    }

    private boolean onPreDraw() {
        if (mOnEnd == null) return true;
        if (mSession != null) {
            int[] location = new int[2];
            getLocationOnScreen(location);
            if (getWidth() != mSession.mWidth || getHeight() != mSession.mHeight
                    || location[0] != mSession.mCrop.left || location[1] != mSession.mCrop.top
                    || getDisplay() == null || getDisplay().getDisplayId() != mSession.mDisplayId
                    || getDisplay().getRotation() != mSession.mRotation) {
                finish(true);
            }
            return true;
        }
        ViewRootImpl root = getViewRootImpl();
        if (root == null || getWidth() <= 0 || getHeight() <= 0
                || !root.getSurfaceControl().isValid()) return true;
        try {
            mSession = new Session(root.getSurfaceControl());
            mSession.start();
        } catch (RuntimeException | LinkageError | OutOfMemoryError exception) {
            Log.w(TAG, "Unable to start LLE surface", exception);
            finish(false);
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    private final class Session implements Choreographer.FrameCallback {
        private final int mWidth = getWidth();
        private final int mHeight = getHeight();
        private final Rect mCrop;
        private final int[] mSurfacePosition = new int[2];
        private final float mNormalizedX;
        private final float mNormalizedY;
        private final int mDisplayId;
        private final int mRotation;
        private final float mRequestedFrameRate;
        private final SurfaceControl mParent;
        private final HandlerThread mThread = new HandlerThread("WaterRipple");
        private final ObjIntConsumer<ScreenshotHardwareBuffer> mCaptureConsumer = this::onCapture;
        private Handler mHandler;
        private volatile boolean mStopped;
        private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEglSurface = EGL14.EGL_NO_SURFACE;
        private Surface mSurface;
        private SurfaceControl mLayer;
        private BLASTBufferQueue mBufferQueue;
        private ScreenCaptureInternal.CaptureArgs mCaptureArgs;
        private Choreographer mChoreographer;
        private long mNativeHandle;
        private boolean mFirstFrame = true;
        private long mFirstFrameNs;
        private boolean mBackgroundReady;
        private long mLastBackgroundNs;
        private HardwareBuffer mPendingBackground;
        private boolean mCaptureInFlight;
        private boolean mCaptureFailed;
        private boolean mCleaned;

        Session(SurfaceControl parent) {
            int[] location = new int[2];
            getLocationOnScreen(location);
            mCrop = new Rect(location[0], location[1], location[0] + mWidth, location[1] + mHeight);
            getLocationInSurface(mSurfacePosition);
            mNormalizedX = (mOriginX - location[0]) / mWidth;
            mNormalizedY = (mOriginY - location[1]) / mHeight;
            Display display = getDisplay();
            mDisplayId = display.getDisplayId();
            mRotation = display.getRotation();
            float refreshRate = 60f;
            if (mHighFrameRate) {
                Display.Mode current = display.getMode();
                for (Display.Mode mode : display.getSupportedModes()) {
                    if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                            && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                        refreshRate = Math.max(refreshRate, mode.getRefreshRate());
                    }
                }
            }
            mRequestedFrameRate = refreshRate;
            mParent = new SurfaceControl(parent, TAG);
        }

        void start() {
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
            mHandler.post(this::initialize);
        }

        synchronized void stop() {
            if (mStopped) return;
            mStopped = true;
            if (mLayer != null) {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.hide(mLayer).apply();
                }
            }
            if (mHandler != null) mHandler.post(this::cleanup);
            else mParent.release();
        }

        private void initialize() {
            try {
                synchronized (this) {
                    if (mStopped) return;
                    mLayer = new SurfaceControl.Builder().setName(TAG).setParent(mParent)
                            .setBLASTLayer().setHidden(true).build();
                    try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                        transaction.setLayer(mLayer, 1)
                                .setPosition(mLayer, mSurfacePosition[0], mSurfacePosition[1])
                                .setWindowCrop(mLayer, mWidth, mHeight).apply();
                    }
                }
                mBufferQueue = new BLASTBufferQueue(TAG, false);
                mBufferQueue.update(mLayer, mWidth, mHeight, PixelFormat.RGBA_8888);
                mSurface = mBufferQueue.createSurface();
                mSurface.setFrameRate(mRequestedFrameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                final int textureLimit = mAnimation == EFFECT_WATER
                        ? WATER_TEXTURE_LIMIT : WATERCOLOR_TEXTURE_LIMIT;
                mCaptureArgs = new ScreenCaptureInternal.CaptureArgs.Builder<>()
                        .setSourceCrop(mCrop)
                        .setFrameScale(Math.min(1f, textureLimit / (float) Math.max(mWidth, mHeight)))
                        .setPixelFormat(PixelFormat.RGBA_8888)
                        .setExcludeLayers(new SurfaceControl[] {mLayer})
                        .build();
                initEgl();
                mNativeHandle = WaterRippleNative.create(mWidth, mHeight, mNormalizedX, mNormalizedY,
                        mAnimation, mHighFrameRate, mFullscreen, mUnlockEffect);
                if (mNativeHandle == 0) throw new IllegalStateException("Native initialization failed");
                if (mAnimation == EFFECT_WATERCOLOR) {
                    uploadResource(1, R.drawable.watercolor_mask1);
                    uploadResource(2, R.drawable.watercolor_mask2);
                    uploadResource(3, R.drawable.watercolor_mask3);
                    uploadResource(4, R.drawable.watercolor_noise);
                    uploadResource(5, R.drawable.waterbrush_tube);
                } else {
                    uploadResource(1, R.drawable.water_ripple_reflection);
                }
                requestBackground();
                mChoreographer = Choreographer.getInstance();
                mChoreographer.postFrameCallback(this);
            } catch (RuntimeException | RemoteException | LinkageError | OutOfMemoryError exception) {
                fail(exception);
            } finally {
                if (mStopped) cleanup();
            }
        }

        private synchronized void onCapture(ScreenshotHardwareBuffer capture, int status) {
            HardwareBuffer buffer = capture != null ? capture.getHardwareBuffer() : null;
            mCaptureInFlight = false;
            if (mStopped || mCleaned || status != 0 || buffer == null) {
                if (buffer != null) buffer.close();
                if (!mStopped && !mCleaned) mCaptureFailed = true;
                return;
            }
            if (mPendingBackground != null) mPendingBackground.close();
            mPendingBackground = buffer;
        }

        private void requestBackground() throws RemoteException {
            synchronized (this) {
                if (mStopped || mCaptureInFlight) return;
                mCaptureInFlight = true;
            }
            WindowManagerGlobal.getWindowManagerService().captureDisplay(mDisplayId, mCaptureArgs,
                    new ScreenCaptureInternal.ScreenCaptureListener(mCaptureConsumer));
        }

        private void updateBackground() {
            HardwareBuffer buffer;
            synchronized (this) {
                if (mCaptureFailed) throw new IllegalStateException("Live background unavailable");
                buffer = mPendingBackground;
                mPendingBackground = null;
            }
            if (buffer == null) return;
            try {
                require(WaterRippleNative.uploadBackground(mNativeHandle, buffer));
                mBackgroundReady = true;
                mLastBackgroundNs = System.nanoTime();
            } finally {
                buffer.close();
            }
        }

        private void uploadResource(int slot, int resourceId) {
            if (mStopped) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resourceId, options);
            if (bitmap == null) throw new IllegalStateException("Effect texture unavailable");
            try {
                require(WaterRippleNative.upload(mNativeHandle, slot, bitmap));
            } finally {
                bitmap.recycle();
            }
        }

        private void initEgl() {
            mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            require(EGL14.eglInitialize(mDisplay, versions, 0, versions, 1));
            int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            require(EGL14.eglChooseConfig(mDisplay, attributes, 0, configs, 0, 1, count, 0)
                    && count[0] > 0);
            mContext = EGL14.eglCreateContext(mDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(mContext != EGL14.EGL_NO_CONTEXT);
            mEglSurface = EGL14.eglCreateWindowSurface(mDisplay, configs[0], mSurface,
                    new int[] {EGL14.EGL_NONE}, 0);
            require(mEglSurface != EGL14.EGL_NO_SURFACE);
            require(EGL14.eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mContext));
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (mStopped) return;
            try {
                updateBackground();
                requestBackground();
                if (!mBackgroundReady) {
                    mChoreographer.postFrameCallback(this);
                    return;
                }
                if (System.nanoTime() - mLastBackgroundNs > SETUP_TIMEOUT_MS * 1_000_000L) {
                    throw new IllegalStateException("Live background capture stalled");
                }
                long elapsedNs = Math.max(0, frameTimeNanos - mStartNs);
                float progress = Math.min(1f, elapsedNs / (mDurationMs * 1_000_000f));
                float opacity = Math.min(1f, (1f - progress) / 0.3f);
                if (mFirstFrame) mFirstFrameNs = frameTimeNanos;
                long simulationNs = Math.max(0, frameTimeNanos - mFirstFrameNs);
                require(WaterRippleNative.draw(mNativeHandle, simulationNs, opacity,
                        SIMULATION_DURATION_MS / (float) mDurationMs));
                require(EGL14.eglSwapBuffers(mDisplay, mEglSurface));
                if (mFirstFrame) {
                    mFirstFrame = false;
                    synchronized (this) {
                        if (!mStopped) {
                            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                                transaction.show(mLayer).apply();
                            }
                        }
                    }
                    mMain.post(() -> {
                        if (!mStopped) mMain.removeCallbacks(mSetupTimeout);
                    });
                }
                if (progress < 1f) mChoreographer.postFrameCallback(this);
                else mMain.post(() -> { if (!mStopped) finish(true); });
            } catch (RuntimeException | RemoteException | LinkageError | OutOfMemoryError exception) {
                fail(exception);
            }
        }

        private void fail(Throwable exception) {
            Log.w(TAG, "LLE effect unavailable; use the platform ripple", exception);
            mMain.post(() -> { if (!mStopped) finish(false); });
            cleanup();
        }

        private void cleanup() {
            if (mChoreographer != null) mChoreographer.removeFrameCallback(this);
            if (mNativeHandle != 0) {
                WaterRippleNative.destroy(mNativeHandle);
                mNativeHandle = 0;
            }
            if (mDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (mEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mDisplay, mEglSurface);
                    mEglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (mContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(mDisplay, mContext);
                    mContext = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglTerminate(mDisplay);
                EGL14.eglReleaseThread();
                mDisplay = EGL14.EGL_NO_DISPLAY;
            }
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            if (mBufferQueue != null) {
                mBufferQueue.destroy();
                mBufferQueue = null;
            }
            synchronized (this) {
                if (mLayer != null) {
                    try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                        transaction.remove(mLayer).apply();
                    }
                    mLayer.release();
                    mLayer = null;
                }
                if (mPendingBackground != null) {
                    mPendingBackground.close();
                    mPendingBackground = null;
                }
                if (!mCleaned) mParent.release();
                mCleaned = true;
            }
            mThread.quitSafely();
        }

        private void require(boolean condition) {
            if (!condition) throw new IllegalStateException("GLES/EGL operation failed");
        }
    }
}
