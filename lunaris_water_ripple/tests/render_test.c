/* Host GLES integration tests. Include the renderers to inspect wave energy and
 * paint continuity as well as the final framebuffer; production builds exclude tests. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "../native/unlock_ripple.c"
#include "../native/unlock_watercolor.c"

static const char *asset_dir;
static unsigned char pixels[320 * 320 * 4];

static void *asset(const char *name, int *width, int *height) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s.rgba", asset_dir, name);
    FILE *file = fopen(path, "rb");
    assert(file);
    uint32_t dimensions[2];
    assert(fread(dimensions, sizeof(dimensions), 1, file) == 1);
    *width = (int) dimensions[0];
    *height = (int) dimensions[1];
    size_t bytes = (size_t) *width * *height * 4;
    void *rgba = malloc(bytes);
    assert(rgba && fread(rgba, bytes, 1, file) == 1);
    fclose(file);
    return rgba;
}

static GLuint background(int blue) {
    unsigned char rgba[4] = {blue ? 12 : 180, 30, blue ? 180 : 12, 255};
    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    assert(glGetError() == GL_NO_ERROR);
    return texture;
}

static long read_frame(int width, int height, bool transparent) {
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    assert(glGetError() == GL_NO_ERROR);
    long color = 0;
    for (int i = 0; i < width * height; ++i) {
        unsigned char *p = pixels + i * 4;
        assert(p[0] <= p[3] + 1 && p[1] <= p[3] + 1 && p[2] <= p[3] + 1);
        if (transparent) assert(p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 0);
        color += (long) p[0] - p[2];
    }
    return color;
}

static double wave_energy(const UnlockRipple *ripple) {
    double energy = 0;
    for (int y = 0; y < ripple->rows; ++y) {
        for (int x = 0; x < ripple->columns; ++x) {
            int i = (y + ripple->padding) * ripple->stride + x + ripple->padding;
            double dx = ripple->heights[i] - ripple->heights[i - 1];
            double dy = ripple->heights[i] - ripple->heights[i + ripple->stride];
            double v = ripple->velocity[i];
            assert(isfinite(dx) && isfinite(dy) && isfinite(v));
            energy += dx * dx + dy * dy + v * v;
        }
    }
    return energy;
}

/* Send a traveling pulse at each side and measure it back inside the screen.
 * A fully absorbing edge fails the lower bound; a hard wall fails the upper.
 * This checks the returned wave rather than a boundary coefficient or formula. */
static void gentle_edge_rebound(int hz, int side) {
    UnlockRipple *ripple = unlock_ripple_create(256, 256, 0.5f, 0.5f, true, true);
    assert(ripple);
    unlock_ripple_set_background(ripple, background(0));
    const unsigned char reflection[] = {180, 180, 180, 255};
    assert(unlock_ripple_upload(ripple, LLE_RIPPLE_TEXTURE_WATER, 1, 1, reflection));
    const int extent = ripple->columns - 1;
    for (int y = 0; y < ripple->detail_rows; ++y) {
        for (int x = 0; x < ripple->stride; ++x) {
            float distance = (side < 2 ? x : y) - ripple->padding;
            if (side % 2) distance = extent - distance;
            const float offset = distance - 16;
            const float height = expf(-offset * offset / (2 * 2.5f * 2.5f));
            const int i = y * ripple->stride + x;
            ripple->heights[i] = height;
            // v = c * dh/dx makes the pulse travel towards the selected edge.
            ripple->velocity[i] = -sqrtf(0.5f) * offset / (2.5f * 2.5f) * height;
        }
    }
    float incident = 0;
    float returned = 0;
    for (int frame = 0; frame <= hz * 3 / 10; ++frame) {
        assert(unlock_ripple_draw(ripple, (uint64_t) frame * UINT64_C(1000000000) / hz, 1, 1));
        const int axis = ripple->padding + (side % 2 ? extent - 8 : 8);
        const int center = ripple->padding + extent / 2;
        const int i = side < 2 ? center * ripple->stride + axis : axis * ripple->stride + center;
        if (frame < hz / 10) incident = fmaxf(incident, ripple->heights[i]);
        if (frame > hz * 16 / 100) returned = fmaxf(returned, ripple->heights[i]);
    }
    const float ratio = returned / incident;
    printf("Edge %d at %d Hz: rebound/incident=%.3f\n", side, hz, ratio);
    assert(incident > 0.5f && ratio > 0.08f && ratio < 0.30f);
    unlock_ripple_destroy(ripple);
}

static void water(int width, int height, float x, float y, int hz, bool fullscreen, float time_scale) {
    UnlockRipple *ripple = unlock_ripple_create(width, height, x, y,
            hz != 60 || time_scale != 1, fullscreen);
    assert(ripple);
    int tw, th;
    void *rgba = asset("water_ripple_reflection", &tw, &th);
    assert(unlock_ripple_upload(ripple, LLE_RIPPLE_TEXTURE_WATER, tw, th, rgba));
    free(rgba);
    double peak_energy = 0;
    bool changed = false;
    const int probe_frame = (int) fmaxf(1, roundf(hz / (5 * time_scale)));
    const int frames = time_scale > 1 ? (int) ceilf(hz * 0.8f / time_scale) : hz * 209 / 100;
    for (int frame = 0; frame <= frames; ++frame) {
        uint64_t time = (uint64_t) frame * UINT64_C(1000000000) / hz;
        unlock_ripple_set_background(ripple, background(0));
        assert(unlock_ripple_draw(ripple, time, 1, time_scale));
        double energy = wave_energy(ripple);
        if (energy > peak_energy) peak_energy = energy;
        if (frame == probe_frame) {
            long red = read_frame(width, height, false);
            unlock_ripple_set_background(ripple, background(1));
            assert(unlock_ripple_draw(ripple, time, 1, time_scale));
            long blue = read_frame(width, height, false);
            assert(red - blue > 1000);
            changed = true;
        }
    }
    double tail_ratio = wave_energy(ripple) / peak_energy;
    printf("Water %dx%d origin %.1f,%.1f %d Hz fullscreen=%d speed=%.3f tail/peak=%.6f\n",
            width, height, x, y, hz, fullscreen, time_scale, tail_ratio);
    assert(changed && peak_energy > 1);
    // A gentle first rebound must still settle instead of building up repeated echoes.
    if (fullscreen && time_scale == 1) assert(tail_ratio < 0.01);
    assert(unlock_ripple_draw(ripple, UINT64_C(2090000000), 0, time_scale));
    read_frame(width, height, true);
    unlock_ripple_destroy(ripple);
}

static void watercolor(int hz) {
    UnlockWatercolor *state = unlock_watercolor_create(180, 320, 0.5f, 0.8f, hz != 60);
    assert(state);
    const char *names[] = {"watercolor_mask1", "watercolor_mask2", "watercolor_mask3",
            "watercolor_noise", "waterbrush_tube"};
    for (int i = 0; i < 5; ++i) {
        int width, height;
        void *rgba = asset(names[i], &width, &height);
        assert(unlock_watercolor_upload(state, i + 1, width, height, rgba));
        free(rgba);
    }
    long red = 0;
    for (int frame = 0; frame <= hz * 4 / 5; ++frame) {
        uint64_t time = (uint64_t) frame * UINT64_C(1000000000) / hz;
        bool seeded = state->density_seeded;
        int density_index = state->density_read_index;
        unlock_watercolor_set_background(state, background(frame > hz / 5));
        // Live updates must retain the paint history, including on duplicate timestamps.
        assert(state->density_seeded == seeded && state->density_read_index == density_index);
        assert(unlock_watercolor_draw(state, time, 1, 1));
        if (frame == hz / 5) red = read_frame(180, 320, false);
        if (frame == hz * 3 / 5) {
            long blue = read_frame(180, 320, false);
            assert(red > 0 && blue < 0);
        }
    }
    assert(unlock_watercolor_draw(state, UINT64_C(800000000), 0, 1));
    read_frame(180, 320, true);
    unlock_watercolor_destroy(state);
    printf("Watercolor %d Hz: live colors, paint continuity and transparent finish passed\n", hz);
}

int main(int argc, char **argv) {
    setbuf(stdout, NULL);
    assert(argc == 2);
    asset_dir = argv[1];
    PFNEGLGETPLATFORMDISPLAYEXTPROC get_display =
            (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
    assert(get_display);
    EGLDisplay display = get_display(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    assert(eglInitialize(display, NULL, NULL));
    const EGLint attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config;
    EGLint count;
    assert(eglChooseConfig(display, attrs, &config, 1, &count) && count == 1);
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    const EGLint surface_attrs[] = {EGL_WIDTH, 320, EGL_HEIGHT, 320, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, surface_attrs);
    assert(eglMakeCurrent(display, surface, surface, context));
    const int rates[] = {60, 90, 120, 144};
    const float origins[][2] = {{0.5f, 0.5f}, {0, 0.5f}, {1, 0.5f}, {0.5f, 0}, {0.5f, 1},
            {0, 0}, {1, 0}, {0, 1}, {1, 1}};
    for (size_t i = 0; i < sizeof(rates) / sizeof(rates[0]); ++i) {
        for (int side = 0; side < 4; ++side) gentle_edge_rebound(rates[i], side);
        for (size_t j = 0; j < sizeof(origins) / sizeof(origins[0]); ++j) {
            water(180, 320, origins[j][0], origins[j][1], rates[i], true, 1);
        }
        water(320, 180, 0.5f, 0.5f, rates[i], true, 1);
        water(256, 256, 0.5f, 0.5f, rates[i], true, 1);
        water(180, 320, 0.5f, 0.8f, rates[i], false, 1);
        water(180, 320, 0.5f, 0.5f, rates[i], true, 800.0f / 2090);
        water(180, 320, 0.5f, 0.5f, rates[i], true, 4);
        watercolor(rates[i]);
    }
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    eglTerminate(display);
    puts("All GLES integration tests passed");
    return 0;
}
