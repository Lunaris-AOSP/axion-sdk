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

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;

final class WaterRippleNative {
    static {
        System.loadLibrary("lunaris_water_ripple");
    }

    static native long create(int width, int height, float x, float y,
            int animation, boolean highFrameRate, boolean fullscreen, boolean unlockEffect);
    static native boolean upload(long handle, int slot, Bitmap bitmap);
    static native boolean uploadBackground(long handle, HardwareBuffer buffer);
    static native boolean draw(long handle, long elapsedNs, float opacity, float timeScale);
    static native void destroy(long handle);

    private WaterRippleNative() {}
}
