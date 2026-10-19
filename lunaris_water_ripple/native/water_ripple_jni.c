#include "unlock_ripple.h"
#include "unlock_watercolor.h"

#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

typedef struct UnlockEffect {
    UnlockRipple *water;
    UnlockWatercolor *watercolor;
} UnlockEffect;

static bool upload_bitmap(JNIEnv *env, UnlockEffect *effect, jobject bitmap, int slot) {
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (bitmap == NULL || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
            || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || info.width == 0 || info.height == 0 || info.width > 2048 || info.height > 2048
            || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    /* Android bitmaps may have row padding. GLES2 has no unpack-row-length support. */
    const size_t row_size = (size_t) info.width * 4;
    unsigned char *packed = NULL;
    if (info.stride != row_size) {
        packed = malloc(row_size * info.height);
        if (packed != NULL) {
            for (uint32_t y = 0; y < info.height; ++y) {
                memcpy(packed + y * row_size, (unsigned char *) pixels + y * info.stride, row_size);
            }
        }
    }
    const void *rgba = packed != NULL ? packed : pixels;
    bool uploaded = false;
    if (info.stride == row_size || packed != NULL) {
        if (effect->watercolor != NULL) {
            uploaded = unlock_watercolor_upload(effect->watercolor, slot,
                    info.width, info.height, rgba);
        } else if (effect->water != NULL && (slot == 0 || slot == 1)) {
            uploaded = unlock_ripple_upload(effect->water,
                    slot == 0 ? LLE_RIPPLE_TEXTURE_BACKGROUND : LLE_RIPPLE_TEXTURE_WATER,
                    info.width, info.height, rgba);
        }
    }
    free(packed);
    AndroidBitmap_unlockPixels(env, bitmap);
    return uploaded;
}

JNIEXPORT jlong JNICALL
Java_com_android_lunaris_waterripple_WaterRippleNative_create(
        JNIEnv *env, jclass clazz, jint width, jint height, jfloat x, jfloat y,
        jint animation, jboolean high_frame_rate, jboolean fullscreen, jboolean unlock_effect) {
    (void) env;
    (void) clazz;
    UnlockEffect *effect = calloc(1, sizeof(*effect));
    if (effect == NULL) return 0;
    if (animation == 1) {
        effect->water = unlock_ripple_create(width, height, x, y, high_frame_rate, fullscreen);
        unlock_ripple_set_unlock_effect(effect->water, unlock_effect);
    } else if (animation == 2) {
        effect->watercolor = unlock_watercolor_create(width, height, x, y, high_frame_rate);
    }
    if (effect->water == NULL && effect->watercolor == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "LunarisWaterRipple", "Renderer initialization failed");
        free(effect);
        return 0;
    }
    return (jlong) (intptr_t) effect;
}

JNIEXPORT jboolean JNICALL
Java_com_android_lunaris_waterripple_WaterRippleNative_upload(
        JNIEnv *env, jclass clazz, jlong handle, jint slot, jobject bitmap) {
    (void) clazz;
    UnlockEffect *effect = (UnlockEffect *) (intptr_t) handle;
    return effect != NULL && upload_bitmap(env, effect, bitmap, slot);
}

/* Import the compositor buffer directly on the GL thread. EGLImage siblings keep
 * the pixels alive after Java closes its HardwareBuffer, including queued draws.
 * No full-frame CPU readback or bitmap allocation is needed for each capture. */
JNIEXPORT jboolean JNICALL
Java_com_android_lunaris_waterripple_WaterRippleNative_uploadBackground(
        JNIEnv *env, jclass clazz, jlong handle, jobject buffer) {
    (void) clazz;
    UnlockEffect *effect = (UnlockEffect *) (intptr_t) handle;
    if (effect == NULL || buffer == NULL) return false;
    AHardwareBuffer *hardware_buffer = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (hardware_buffer == NULL) return false;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get_client_buffer =
            (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress("eglGetNativeClientBufferANDROID");
    PFNEGLCREATEIMAGEKHRPROC create_image =
            (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC destroy_image =
            (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_target =
            (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if (!get_client_buffer || !create_image || !destroy_image || !image_target) return false;
    const EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLDisplay display = eglGetCurrentDisplay();
    EGLImageKHR image = create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
            get_client_buffer(hardware_buffer), attributes);
    if (image == EGL_NO_IMAGE_KHR) return false;
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    image_target(GL_TEXTURE_2D, image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    destroy_image(display, image);
    if (texture == 0 || glGetError() != GL_NO_ERROR) {
        glDeleteTextures(1, &texture);
        return false;
    }
    if (effect->watercolor != NULL) unlock_watercolor_set_background(effect->watercolor, texture);
    else unlock_ripple_set_background(effect->water, texture);
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_android_lunaris_waterripple_WaterRippleNative_draw(
        JNIEnv *env, jclass clazz, jlong handle, jlong elapsed_ns, jfloat opacity, jfloat time_scale) {
    (void) env;
    (void) clazz;
    UnlockEffect *effect = (UnlockEffect *) (intptr_t) handle;
    if (effect == NULL) return false;
    const uint64_t elapsed = elapsed_ns > 0 ? (uint64_t) elapsed_ns : 0;
    return effect->watercolor != NULL
            ? unlock_watercolor_draw(effect->watercolor, elapsed, opacity, time_scale)
            : unlock_ripple_draw(effect->water, elapsed, opacity, time_scale);
}

JNIEXPORT void JNICALL
Java_com_android_lunaris_waterripple_WaterRippleNative_destroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    UnlockEffect *effect = (UnlockEffect *) (intptr_t) handle;
    if (effect == NULL) return;
    unlock_ripple_destroy(effect->water);
    unlock_watercolor_destroy(effect->watercolor);
    free(effect);
}
