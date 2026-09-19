#ifndef LUNARIS_UNLOCK_RIPPLE_H
#define LUNARIS_UNLOCK_RIPPLE_H

#include "ripple_gles_pipeline.h"

typedef struct UnlockRipple UnlockRipple;

/* All operations belong to one thread with a current GLES2 context. */
UnlockRipple *unlock_ripple_create(int width, int height, float x, float y,
        bool high_frame_rate, bool fullscreen);
bool unlock_ripple_upload(UnlockRipple *ripple, LleRippleTextureSlot slot,
        int width, int height, const void *rgba);
/* Takes ownership of a GL_TEXTURE_2D containing the current display capture. */
void unlock_ripple_set_background(UnlockRipple *ripple, GLuint texture);
bool unlock_ripple_draw(UnlockRipple *ripple, uint64_t elapsed_ns, float opacity, float time_scale);
void unlock_ripple_destroy(UnlockRipple *ripple);

#endif
