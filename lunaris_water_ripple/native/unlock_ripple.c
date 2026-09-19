#include "unlock_ripple.h"

#include "ripple_core.h"
#include "ripple_gles_overlay.h"
#include "unlock_clock.h"

#include <math.h>
#include <stdlib.h>

/* Padding keeps an impulse at a physical display edge inside the solver domain. */
enum { LOCAL_PADDING = 4, EDGE_PADDING = 8, MAX_CELLS = 96 };

struct UnlockRipple {
    LleRippleGles gles;
    LleRippleOverlay overlay;
    LleRippleRenderArgs args;
    float mvp[16];
    float *vertices;
    float *heights;
    float *velocity;
    float *gpu_heights;
    uint16_t *indices;
    int columns;
    int rows;
    int stride;
    int detail_rows;
    int padding;
    UnlockClock clock;
    bool high_frame_rate;
    bool fullscreen;
};

/* A rounded, slightly wider impulse produces a fuller crest without the sharp
 * center and high-frequency ripples of a larger cone. Fullscreen padding keeps
 * the entire impulse inside the solver, including origins on edges and corners. */
static void inject_fullscreen_wave(UnlockRipple *ripple, float cell_x, float cell_y) {
    const float radius = 4.0f;
    for (int y = (int) floorf(cell_y - radius); y <= (int) ceilf(cell_y + radius); ++y) {
        for (int x = (int) floorf(cell_x - radius); x <= (int) ceilf(cell_x + radius); ++x) {
            const float dx = x - cell_x;
            const float dy = y - cell_y;
            const float distance = sqrtf(dx * dx + dy * dy);
            if (distance >= radius) continue;
            ripple->velocity[y * ripple->stride + x] +=
                    3.0f * (1.0f + cosf(3.14159265359f * distance / radius));
        }
    }
}

void unlock_ripple_destroy(UnlockRipple *ripple) {
    if (ripple == NULL) return;
    lle_ripple_overlay_destroy(&ripple->overlay);
    lle_ripple_gles_destroy(&ripple->gles);
    free(ripple->vertices);
    free(ripple->heights);
    free(ripple->velocity);
    free(ripple->gpu_heights);
    free(ripple->indices);
    free(ripple);
}

UnlockRipple *unlock_ripple_create(int width, int height, float x, float y,
        bool high_frame_rate, bool fullscreen) {
    if (width <= 0 || height <= 0 || !isfinite(x) || !isfinite(y)) return NULL;
    UnlockRipple *ripple = calloc(1, sizeof(*ripple));
    if (ripple == NULL) return NULL;
    ripple->high_frame_rate = high_frame_rate;
    ripple->fullscreen = fullscreen;
    /* Square cells in display pixels, including tablets, rotations and folded displays.
     * Use an orthographic mesh without the stock S3 crop/shear so HAL coordinates map
     * exactly to the visible wave origin. The recovered solver and shaders are unchanged. */
    const int longest = width > height ? width : height;
    ripple->columns = 2 * (int) fmaxf(8, (float) MAX_CELLS * width / longest / 2) + 1;
    ripple->rows = 2 * (int) fmaxf(8, (float) MAX_CELLS * height / longest / 2) + 1;
    ripple->padding = fullscreen ? EDGE_PADDING : LOCAL_PADDING;
    ripple->stride = ripple->columns + 2 * ripple->padding;
    ripple->detail_rows = ripple->rows + 2 * ripple->padding;
    const int count = ripple->columns * ripple->rows;
    const int detail_count = ripple->stride * ripple->detail_rows;
    const int index_count = (ripple->columns - 1) * (ripple->rows - 1) * 6;
    ripple->vertices = calloc((size_t) count * 3, sizeof(float));
    ripple->gpu_heights = calloc((size_t) count * 3, sizeof(float));
    ripple->heights = calloc((size_t) detail_count, sizeof(float));
    ripple->velocity = calloc((size_t) detail_count, sizeof(float));
    ripple->indices = calloc((size_t) index_count, sizeof(uint16_t));
    if (!ripple->vertices || !ripple->gpu_heights || !ripple->heights
            || !ripple->velocity || !ripple->indices) goto fail;

    const int mesh_width = (ripple->columns - 1) / 2;
    const int mesh_height = (ripple->rows - 1) / 2;
    int offset = 0;
    for (int row = 0; row < ripple->rows; ++row) {
        for (int col = 0; col < ripple->columns; ++col) {
            const int vertex = row * ripple->columns + col;
            ripple->vertices[vertex * 3] = col * 0.5f - mesh_width * 0.5f;
            ripple->vertices[vertex * 3 + 1] = mesh_height * 0.5f - row * 0.5f;
            if (row + 1 < ripple->rows && col + 1 < ripple->columns) {
                ripple->indices[offset++] = vertex;
                ripple->indices[offset++] = vertex + ripple->columns;
                ripple->indices[offset++] = vertex + 1;
                ripple->indices[offset++] = vertex + 1;
                ripple->indices[offset++] = vertex + ripple->columns;
                ripple->indices[offset++] = vertex + ripple->columns + 1;
            }
        }
    }
    ripple->mvp[0] = 2.0f / mesh_width;
    ripple->mvp[5] = 2.0f / mesh_height;
    ripple->mvp[10] = 1;
    ripple->mvp[15] = 1;
    ripple->args = (LleRippleRenderArgs) {
        .vertices = ripple->vertices, .heights = ripple->gpu_heights,
        .indices = ripple->indices, .vertex_float_count = count * 3,
        .height_float_count = count * 3, .index_count = index_count,
        .mvp = ripple->mvp, .viewport_width = width, .viewport_height = height,
        .mesh_width = mesh_width, .mesh_height = mesh_height,
        .detail_width = ripple->columns, .detail_height = ripple->rows,
        .refractive_index = 0.93f, .reflection_ratio = fullscreen ? 0.14f : 0.13f,
        .alpha_ratio_1 = 1, .alpha_ratio_2 = 1, .fresnel_ratio = 0.1f,
        .specular_ratio = 0.5f, .exponent_ratio = 20,
    };
    char error[512];
    if (!lle_ripple_gles_init(&ripple->gles, error, sizeof(error))
            || !lle_ripple_overlay_init(&ripple->overlay, error, sizeof(error))) goto fail;

    const float cell_x = ripple->padding + fminf(1, fmaxf(0, x)) * (ripple->columns - 1);
    const float cell_y = ripple->padding + fminf(1, fmaxf(0, y)) * (ripple->rows - 1);
    if (fullscreen) {
        inject_fullscreen_wave(ripple, cell_x, cell_y);
    } else {
        lle_ripple_inject(ripple->velocity, 50, 50, ripple->stride, ripple->detail_rows,
                (cell_x / ripple->stride - 0.5f) * 50,
                (cell_y / ripple->detail_rows - 0.5f) * 50, 2.0f);
    }
    return ripple;
fail:
    unlock_ripple_destroy(ripple);
    return NULL;
}

bool unlock_ripple_upload(UnlockRipple *ripple, LleRippleTextureSlot slot,
        int width, int height, const void *rgba) {
    char error[512];
    return ripple != NULL && lle_ripple_gles_upload_rgba(&ripple->gles, slot,
            width, height, rgba, error, sizeof(error));
}

void unlock_ripple_set_background(UnlockRipple *ripple, GLuint texture) {
    lle_ripple_gles_free_texture(&ripple->gles, LLE_RIPPLE_TEXTURE_BACKGROUND);
    ripple->gles.background_texture = texture;
}

/* A reflecting rim sits beyond the visible mesh. Waves cross the damped band
 * going out and returning, leaving only a soft rebound at each of the four sides. */
static void reflect_rim(UnlockRipple *ripple) {
    const int stride = ripple->stride;
    const int last_row = ripple->detail_rows - 1;
    for (int y = 1; y < last_row; ++y) {
        const int row = y * stride;
        ripple->heights[row] = ripple->heights[row + 2];
        ripple->velocity[row] = ripple->velocity[row + 2];
        ripple->heights[row + stride - 1] = ripple->heights[row + stride - 3];
        ripple->velocity[row + stride - 1] = ripple->velocity[row + stride - 3];
    }
    for (int x = 0; x < stride; ++x) {
        ripple->heights[x] = ripple->heights[2 * stride + x];
        ripple->velocity[x] = ripple->velocity[2 * stride + x];
        ripple->heights[last_row * stride + x] = ripple->heights[(last_row - 2) * stride + x];
        ripple->velocity[last_row * stride + x] = ripple->velocity[(last_row - 2) * stride + x];
    }
}

/* Fractional steps use the same attenuation per unit of simulation time at
 * every refresh rate; the smooth ramp avoids a hard seam at the visible edge. */
static void damp_edges(UnlockRipple *ripple, float step) {
    float attenuation[EDGE_PADDING + 1];
    for (int d = 0; d <= EDGE_PADDING; ++d) {
        float depth = (float) d / EDGE_PADDING;
        attenuation[d] = powf(1.0f - 0.14f * depth * depth, step);
    }
    const int right = ripple->padding + ripple->columns - 1;
    const int bottom = ripple->padding + ripple->rows - 1;
    for (int y = 0; y < ripple->detail_rows; ++y) {
        const int dy = y < ripple->padding ? ripple->padding - y : y > bottom ? y - bottom : 0;
        for (int x = 0; x < ripple->stride; ++x) {
            const int dx = x < ripple->padding ? ripple->padding - x : x > right ? x - right : 0;
            const int distance = dx > dy ? dx : dy;
            if (distance == 0) continue;
            ripple->heights[y * ripple->stride + x] *= attenuation[distance];
            ripple->velocity[y * ripple->stride + x] *= attenuation[distance];
        }
    }
}

bool unlock_ripple_draw(UnlockRipple *ripple, uint64_t elapsed_ns, float opacity, float time_scale) {
    if (ripple == NULL || !isfinite(opacity) || !isfinite(time_scale) || time_scale <= 0) return false;
    // Detect stalls in real time before applying the requested playback speed.
    float ticks = unlock_clock_advance(&ripple->clock, elapsed_ns, ripple->high_frame_rate) * time_scale;
    /* Four solver ticks per stock tick let the wave cross the display during
     * unlock. Equal steps of at most half a tick avoid exciting high-frequency
     * modes at non-integral refresh rates with the low fullscreen damping. */
    ticks *= ripple->fullscreen ? 4.0f : 1.0f;
    const int substeps = (int) ceilf(ticks / (ripple->fullscreen ? 0.5f : 1.0f));
    for (int i = 0; i < substeps; ++i) {
        const float step = ticks / substeps;
        if (ripple->fullscreen) reflect_rim(ripple);
        lle_ripple_move_adaptive(ripple->velocity, ripple->heights,
                1, 1, ripple->stride - 1, ripple->detail_rows - 1,
                ripple->stride, ripple->detail_rows, false,
                ripple->fullscreen ? 0.992f : 0.94f, 0.5f, step);
        if (ripple->fullscreen) damp_edges(ripple, step);
    }
    for (int row = 0; row < ripple->rows; ++row) {
        for (int col = 0; col < ripple->columns; ++col) {
            const int src = (row + ripple->padding) * ripple->stride + col + ripple->padding;
            const int dst = (row * ripple->columns + col) * 3;
            ripple->gpu_heights[dst] = ripple->heights[src];
            ripple->gpu_heights[dst + 1] = ripple->heights[src - 1];
            ripple->gpu_heights[dst + 2] = ripple->heights[src + ripple->stride];
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    LleRippleOverlayOptions options;
    lle_ripple_overlay_default_options(&options);
    if (ripple->fullscreen) {
        // Keep the shoulders of the crest visible as the broader wave settles.
        options.mask_low = 0.03f;
        options.mask_high = 0.16f;
    }
    options.opacity = fminf(1, fmaxf(0, opacity));
    char error[512];
    return lle_ripple_gles_render_normal_variant(&ripple->gles, &ripple->overlay,
            LLE_RIPPLE_NORMAL_COMPOSITE_TRANSPARENT_DELTA, &ripple->args,
            &options, error, sizeof(error));
}
