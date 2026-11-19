#pragma once
#include <pebble.h>

// Shared look: colours that follow what the mug is doing, like Pebble's own
// Weather and Health apps, and fonts sized for each screen.

#define UI_LARGE (PBL_DISPLAY_WIDTH >= 200)

GColor ui_state_color(void);      // background for the current mug state
GColor ui_text_color(void);       // text on that background
GColor ui_accent_color(void);     // menu highlight

GFont ui_font_label(void);        // "HEATING"
GFont ui_font_big(void);          // the drink temperature
GFont ui_font_line(void);         // "Heating to 140°F"
GFont ui_font_small(void);        // "58 min left"
GFont ui_font_hint(void);         // wrapped hints

// A small mug: body, handle, and liquid filled to `fill_percent`.
void ui_draw_mug(GContext *ctx, GRect box, GColor stroke, GColor liquid, int fill_percent);
// A horizontal battery glyph with `percent` filled.
void ui_draw_battery(GContext *ctx, GRect box, GColor stroke, int percent, bool charging);
// Text centered in `box`; returns the height it took.
int16_t ui_draw_text(GContext *ctx, const char *text, GFont font, GRect box, GTextAlignment align,
                     GColor color);
