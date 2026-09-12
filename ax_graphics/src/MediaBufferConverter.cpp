/*
 * Copyright 2025-2026 AxionOS
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

#include "ax_graphics/MediaBufferConverter.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android-base/properties.h>
#include <system/graphics.h>
#include <vndk/hardware_buffer.h>

namespace axion::graphics {
namespace {

constexpr char kUseOpenGlForMediaProperty[] = "persist.sys.vk_use_ogl_for_media";

bool isVideoOrHdrDataspace(int32_t dataspace) {
    if (dataspace == 0) {
        return false;
    }

    const int32_t standard = dataspace & HAL_DATASPACE_STANDARD_MASK;
    const int32_t transfer = dataspace & HAL_DATASPACE_TRANSFER_MASK;

    switch (standard) {
        case HAL_DATASPACE_STANDARD_BT601_625:
        case HAL_DATASPACE_STANDARD_BT601_625_UNADJUSTED:
        case HAL_DATASPACE_STANDARD_BT601_525:
        case HAL_DATASPACE_STANDARD_BT601_525_UNADJUSTED:
        case HAL_DATASPACE_STANDARD_BT2020:
        case HAL_DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE:
            return true;
        case HAL_DATASPACE_STANDARD_BT709: {
            const bool limited = (dataspace & HAL_DATASPACE_RANGE_MASK) == HAL_DATASPACE_RANGE_LIMITED;
            return transfer == HAL_DATASPACE_TRANSFER_SMPTE_170M || limited;
        }
        default:
            return transfer == HAL_DATASPACE_TRANSFER_ST2084 || transfer == HAL_DATASPACE_TRANSFER_HLG;
    }
}

bool isYuvOrMediaPixelFormat(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_YCbCr_P010:
        case HAL_PIXEL_FORMAT_YCBCR_422_SP:
        case HAL_PIXEL_FORMAT_YCRCB_420_SP:
        case HAL_PIXEL_FORMAT_YCBCR_422_I:
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
        case HAL_PIXEL_FORMAT_Y8:
        case HAL_PIXEL_FORMAT_Y16:
        case HAL_PIXEL_FORMAT_YV12:
        case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
            return true;
        default:
            return (format & 0x70000000) != 0 || (format >= 0x100 && format <= 0x1FF);
    }
}

bool isHdrRgbPixelFormat(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
        case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
        case 0x36:
            return true;
        default:
            return false;
    }
}

bool hasMediaUsage(uint64_t usage) {
    constexpr uint64_t kMediaUsageMask =
            AHARDWAREBUFFER_USAGE_VIDEO_ENCODE |
            AHARDWAREBUFFER_USAGE_CAMERA_READ |
            AHARDWAREBUFFER_USAGE_CAMERA_WRITE |
            0x00010000ULL |
            0x00020000ULL |
            0x00040000ULL |
            0x00400000ULL |
            0x08000000ULL;
    return (usage & kMediaUsageMask) != 0;
}

class ScopedEglState {
public:
    ScopedEglState()
          : mPrevDisplay(eglGetCurrentDisplay()),
            mPrevContext(eglGetCurrentContext()),
            mPrevDraw(eglGetCurrentSurface(EGL_DRAW)),
            mPrevRead(eglGetCurrentSurface(EGL_READ)) {
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &mPrevFbo);
    }

    ~ScopedEglState() {
        if (mPrevDisplay != EGL_NO_DISPLAY && mPrevContext != EGL_NO_CONTEXT) {
            eglMakeCurrent(mPrevDisplay, mPrevDraw, mPrevRead, mPrevContext);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, mPrevFbo);
    }

private:
    EGLDisplay mPrevDisplay = EGL_NO_DISPLAY;
    EGLContext mPrevContext = EGL_NO_CONTEXT;
    EGLSurface mPrevDraw = EGL_NO_SURFACE;
    EGLSurface mPrevRead = EGL_NO_SURFACE;
    GLint mPrevFbo = 0;
};

class AutoEglImage {
public:
    AutoEglImage(EGLDisplay display, EGLImageKHR image) : mDisplay(display), mImage(image) {}

    ~AutoEglImage() {
        if (mDisplay != EGL_NO_DISPLAY && mImage != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mDisplay, mImage);
        }
    }

    EGLImageKHR get() const { return mImage; }
    bool isValid() const { return mImage != EGL_NO_IMAGE_KHR; }

private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
    EGLImageKHR mImage = EGL_NO_IMAGE_KHR;
};

class AutoGlTexture {
public:
    AutoGlTexture() { glGenTextures(1, &mTexture); }

    ~AutoGlTexture() {
        if (mTexture != 0) {
            glDeleteTextures(1, &mTexture);
        }
    }

    GLuint get() const { return mTexture; }

private:
    GLuint mTexture = 0;
};

class AutoGlFramebuffer {
public:
    AutoGlFramebuffer() { glGenFramebuffers(1, &mFbo); }

    ~AutoGlFramebuffer() {
        if (mFbo != 0) {
            glDeleteFramebuffers(1, &mFbo);
        }
    }

    GLuint get() const { return mFbo; }

private:
    GLuint mFbo = 0;
};

class GlBlitProgram {
public:
    static GlBlitProgram& getInstance() {
        static GlBlitProgram sInstance;
        return sInstance;
    }

    bool ensureInitialized() {
        if (mProgram != 0) {
            return true;
        }

        const char* vertSource =
                "attribute vec2 aPos;\n"
                "varying vec2 vTex;\n"
                "void main() {\n"
                "  vTex = (aPos + 1.0) * 0.5;\n"
                "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
                "}\n";

        const char* fragSource =
                "#extension GL_OES_EGL_image_external : require\n"
                "precision mediump float;\n"
                "varying vec2 vTex;\n"
                "uniform samplerExternalOES uTex;\n"
                "void main() {\n"
                "  gl_FragColor = texture2D(uTex, vTex);\n"
                "}\n";

        GLuint vs = compileShader(GL_VERTEX_SHADER, vertSource);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragSource);
        if (vs == 0 || fs == 0) {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            return false;
        }

        mProgram = glCreateProgram();
        glAttachShader(mProgram, vs);
        glAttachShader(mProgram, fs);
        glLinkProgram(mProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        GLint linked = 0;
        glGetProgramiv(mProgram, GL_LINK_STATUS, &linked);
        if (!linked) {
            glDeleteProgram(mProgram);
            mProgram = 0;
            return false;
        }

        mPosLoc = glGetAttribLocation(mProgram, "aPos");
        mTexLoc = glGetUniformLocation(mProgram, "uTex");
        return true;
    }

    void draw(GLuint srcTexture, uint32_t width, uint32_t height) {
        glViewport(0, 0, width, height);
        glUseProgram(mProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, srcTexture);
        glUniform1i(mTexLoc, 0);

        static const GLfloat kQuadCoords[] = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
        };

        glEnableVertexAttribArray(mPosLoc);
        glVertexAttribPointer(mPosLoc, 2, GL_FLOAT, GL_FALSE, 0, kQuadCoords);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(mPosLoc);
    }

private:
    GlBlitProgram() = default;

    GLuint compileShader(GLenum type, const char* source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    GLuint mProgram = 0;
    GLint mPosLoc = -1;
    GLint mTexLoc = -1;
};

class EglBlitContext {
public:
    static EglBlitContext& getInstance() {
        static thread_local EglBlitContext sInstance;
        return sInstance;
    }

    bool ensureInitialized(EGLDisplay preferredDisplay) {
        if (mInitialized && mDisplay == preferredDisplay && preferredDisplay != EGL_NO_DISPLAY) {
            return true;
        }

        mDisplay = preferredDisplay != EGL_NO_DISPLAY ? preferredDisplay
                                                      : eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL_NO_DISPLAY || eglInitialize(mDisplay, nullptr, nullptr) == EGL_FALSE) {
            return false;
        }

        const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };

        EGLConfig config;
        EGLint numConfigs = 0;
        if (!eglChooseConfig(mDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
            return false;
        }

        const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
        };

        mContext = eglCreateContext(mDisplay, config, EGL_NO_CONTEXT, contextAttribs);
        if (mContext == EGL_NO_CONTEXT) {
            return false;
        }

        const EGLint pbufferAttribs[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE
        };

        mPbuffer = eglCreatePbufferSurface(mDisplay, config, pbufferAttribs);
        if (mPbuffer == EGL_NO_SURFACE) {
            eglDestroyContext(mDisplay, mContext);
            mContext = EGL_NO_CONTEXT;
            return false;
        }

        mInitialized = true;
        return true;
    }

    bool makeCurrent() {
        if (!mInitialized) return false;
        return eglMakeCurrent(mDisplay, mPbuffer, mPbuffer, mContext) == EGL_TRUE;
    }

    EGLDisplay display() const { return mDisplay; }

private:
    EglBlitContext() = default;

    ~EglBlitContext() {
        if (mDisplay != EGL_NO_DISPLAY) {
            if (mPbuffer != EGL_NO_SURFACE) {
                eglDestroySurface(mDisplay, mPbuffer);
            }
            if (mContext != EGL_NO_CONTEXT) {
                eglDestroyContext(mDisplay, mContext);
            }
        }
    }

    bool mInitialized = false;
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
    EGLContext mContext = EGL_NO_CONTEXT;
    EGLSurface mPbuffer = EGL_NO_SURFACE;
};

}

bool MediaBufferConverter::isConversionEnabled() {
    static const bool sEnabled = android::base::GetBoolProperty(kUseOpenGlForMediaProperty, false);
    return sEnabled;
}

bool MediaBufferConverter::isMediaOrHdrBuffer(uint32_t format, uint64_t usage, int32_t dataspace) {
    if (!isConversionEnabled()) {
        return false;
    }
    if (isYuvOrMediaPixelFormat(format)) {
        return true;
    }
    if (hasMediaUsage(usage)) {
        return true;
    }
    if (isHdrRgbPixelFormat(format) && isVideoOrHdrDataspace(dataspace)) {
        return true;
    }
    return isVideoOrHdrDataspace(dataspace);
}

bool MediaBufferConverter::isMediaOrHdrBuffer(const AHardwareBuffer_Desc& desc, int32_t dataspace) {
    return isMediaOrHdrBuffer(desc.format, desc.usage, dataspace);
}

AHardwareBuffer* MediaBufferConverter::convertToRgba8888(AHardwareBuffer* srcBuffer,
                                                        AHardwareBuffer* existingDst) {
    if (!srcBuffer) {
        return nullptr;
    }

    AHardwareBuffer_Desc srcDesc;
    AHardwareBuffer_describe(srcBuffer, &srcDesc);

    AHardwareBuffer* dstBuffer = existingDst;
    if (dstBuffer) {
        AHardwareBuffer_Desc dstDesc;
        AHardwareBuffer_describe(dstBuffer, &dstDesc);
        if (dstDesc.width != srcDesc.width || dstDesc.height != srcDesc.height) {
            AHardwareBuffer_release(dstBuffer);
            dstBuffer = nullptr;
        }
    }

    if (!dstBuffer) {
        AHardwareBuffer_Desc dstDesc = srcDesc;
        dstDesc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        dstDesc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                AHARDWAREBUFFER_USAGE_CPU_READ_NEVER |
                AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER;
        dstDesc.layers = 1;
        if (AHardwareBuffer_allocate(&dstDesc, &dstBuffer) != 0) {
            return nullptr;
        }
    }

    ScopedEglState scopedState;

    EglBlitContext& blitCtx = EglBlitContext::getInstance();
    if (!blitCtx.ensureInitialized(eglGetCurrentDisplay()) || !blitCtx.makeCurrent()) {
        if (!existingDst || dstBuffer != existingDst) {
            AHardwareBuffer_release(dstBuffer);
        }
        return nullptr;
    }

    const EGLDisplay display = blitCtx.display();
    const EGLClientBuffer srcClient = eglGetNativeClientBufferANDROID(srcBuffer);
    const EGLClientBuffer dstClient = eglGetNativeClientBufferANDROID(dstBuffer);
    if (!srcClient || !dstClient) {
        if (!existingDst || dstBuffer != existingDst) {
            AHardwareBuffer_release(dstBuffer);
        }
        return nullptr;
    }

    AutoEglImage srcEgl(display, eglCreateImageKHR(display, EGL_NO_CONTEXT,
                                                  EGL_NATIVE_BUFFER_ANDROID, srcClient, nullptr));
    AutoEglImage dstEgl(display, eglCreateImageKHR(display, EGL_NO_CONTEXT,
                                                  EGL_NATIVE_BUFFER_ANDROID, dstClient, nullptr));
    if (!srcEgl.isValid() || !dstEgl.isValid()) {
        if (!existingDst || dstBuffer != existingDst) {
            AHardwareBuffer_release(dstBuffer);
        }
        return nullptr;
    }

    AutoGlTexture srcTex;
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, srcTex.get());
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, srcEgl.get());
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    AutoGlTexture dstTex;
    glBindTexture(GL_TEXTURE_2D, dstTex.get());
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, dstEgl.get());

    AutoGlFramebuffer fbo;
    glBindFramebuffer(GL_FRAMEBUFFER, fbo.get());
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dstTex.get(), 0);

    GlBlitProgram& program = GlBlitProgram::getInstance();
    if (!program.ensureInitialized()) {
        if (!existingDst || dstBuffer != existingDst) {
            AHardwareBuffer_release(dstBuffer);
        }
        return nullptr;
    }

    program.draw(srcTex.get(), srcDesc.width, srcDesc.height);

    EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, nullptr);
    glFlush();
    if (sync != EGL_NO_SYNC_KHR) {
        eglClientWaitSyncKHR(display, sync, 0, 1000000000ULL);
        eglDestroySyncKHR(display, sync);
    }

    return dstBuffer;
}

} // namespace axion::graphics
