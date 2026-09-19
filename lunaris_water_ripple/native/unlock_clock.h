#ifndef LUNARIS_UNLOCK_CLOCK_H
#define LUNARIS_UNLOCK_CLOCK_H

#include "ripple_core.h"

typedef struct UnlockClock {
    uint64_t previous_ns;
    uint64_t tick_credit;
    bool started;
} UnlockClock;

/* HFR uses LLE's elapsed-time solver, dropping compositor stalls. The stock
 * path consumes whole 60 Hz ticks, with at most four updates after a stall. */
static inline float unlock_clock_advance(UnlockClock *clock, uint64_t elapsed_ns, bool hfr) {
    if (!clock->started) {
        clock->started = true;
        clock->previous_ns = elapsed_ns;
        return 0;
    }
    if (elapsed_ns <= clock->previous_ns) return 0;
    uint64_t delta = elapsed_ns - clock->previous_ns;
    clock->previous_ns = elapsed_ns;
    if (hfr) {
        if (delta > UINT64_C(35714286)) return 0;
        return lle_ripple_tick_units_to_stock_ticks(lle_ripple_elapsed_ns_to_tick_units(delta));
    }
    if (delta > UINT64_C(66666667)) delta = UINT64_C(66666667);
    unsigned ticks = lle_ripple_consume_logical_ticks(&clock->tick_credit, delta);
    return ticks > 4 ? 4 : ticks;
}

#endif
