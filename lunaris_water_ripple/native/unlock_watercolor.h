#ifndef LUNARIS_UNLOCK_WATERCOLOR_H
#define LUNARIS_UNLOCK_WATERCOLOR_H

#include <stdbool.h>
#include <stdint.h>

typedef struct WatercolorState UnlockWatercolor;

/* All operations require the owning thread's GLES2 context. */
UnlockWatercolor *unlock_watercolor_create(int width, int height, float x, float y,
        bool high_frame_rate);
/* Slots: 0 background, 1/2/3 masks, 4 noise source, 5 tube. */
bool unlock_watercolor_upload(UnlockWatercolor *state, int slot,
        int width, int height, const void *rgba);
/* Takes ownership without resetting the evolving paint density. */
void unlock_watercolor_set_background(UnlockWatercolor *state, unsigned int texture);
bool unlock_watercolor_draw(UnlockWatercolor *state, uint64_t elapsed_ns, float opacity,
        float time_scale);
void unlock_watercolor_destroy(UnlockWatercolor *state);

#endif
