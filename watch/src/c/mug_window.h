#pragma once
#include <pebble.h>

void mug_window_push(void);
// Redraw with the latest state (called when the phone sends something).
void mug_window_refresh(void);
// Send a new target (tenths of the display unit, 0 = heater off) and show it until the mug confirms.
void mug_window_set_target(int16_t tenths);
// Turn the heater back on at the last target.
void mug_window_heater_on(void);
