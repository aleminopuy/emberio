#include "ui.h"
#include "state.h"

GColor ui_state_color(void) {
#if defined(PBL_COLOR)
  MugState *s = state_get();
  if (!state_is_live()) {
    return GColorDarkGray;
  }
  if (s->target_temp == 0) {
    return GColorOxfordBlue;  // heater off
  }
  switch ((LiquidState)s->liquid) {
    case LiquidHeating: return GColorOrange;
    case LiquidCooling: return GColorVividCerulean;
    case LiquidPerfect: return GColorIslamicGreen;
    case LiquidFilling: return GColorCobaltBlue;
    default: return GColorOxfordBlue;
  }
#else
  return GColorWhite;
#endif
}

GColor ui_text_color(void) {
  return PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack);
}

GColor ui_accent_color(void) {
  return PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack);
}

GFont ui_font_label(void) {
  return fonts_get_system_font(UI_LARGE ? FONT_KEY_GOTHIC_18_BOLD : FONT_KEY_GOTHIC_14_BOLD);
}

GFont ui_font_big(void) {
  // The 60px numbers only exist on the big screens (Time 2, Round 2).
#if UI_LARGE
  return fonts_get_system_font(FONT_KEY_LECO_60_NUMBERS_AM_PM);
#else
  return fonts_get_system_font(FONT_KEY_LECO_42_NUMBERS);
#endif
}

GFont ui_font_line(void) {
  return fonts_get_system_font(UI_LARGE ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD);
}

GFont ui_font_small(void) {
  return fonts_get_system_font(UI_LARGE ? FONT_KEY_GOTHIC_24 : FONT_KEY_GOTHIC_18);
}

GFont ui_font_hint(void) {
  return fonts_get_system_font(UI_LARGE ? FONT_KEY_GOTHIC_24 : FONT_KEY_GOTHIC_18);
}

int16_t ui_draw_text(GContext *ctx, const char *text, GFont font, GRect box, GTextAlignment align,
                     GColor color) {
  graphics_context_set_text_color(ctx, color);
  GSize size = graphics_text_layout_get_content_size(text, font, box, GTextOverflowModeWordWrap,
                                                     align);
  graphics_draw_text(ctx, text, font, box, GTextOverflowModeWordWrap, align, NULL);
  return size.h;
}

void ui_draw_mug(GContext *ctx, GRect box, GColor stroke, GColor liquid, int fill_percent) {
  // Body takes ~70% of the width; the handle the rest.
  int16_t body_w = box.size.w * 7 / 10;
  int16_t stroke_w = box.size.w >= 60 ? 4 : 3;
  GRect body = GRect(box.origin.x, box.origin.y, body_w, box.size.h);
  int16_t handle_h = box.size.h / 2;
  GRect handle = GRect(box.origin.x + body_w - stroke_w, box.origin.y + box.size.h / 5,
                       box.size.w - body_w + stroke_w, handle_h);

  graphics_context_set_stroke_color(ctx, stroke);
  graphics_context_set_fill_color(ctx, stroke);
  graphics_context_set_stroke_width(ctx, stroke_w);

  // Handle: an outlined rounded rect, half hidden behind the body.
  graphics_draw_round_rect(ctx, handle, handle.size.h / 3);

  // Liquid.
  if (fill_percent > 0) {
    int16_t inner_h = body.size.h - stroke_w * 2;
    int16_t liquid_h = inner_h * (fill_percent > 100 ? 100 : fill_percent) / 100;
    GRect fill = GRect(body.origin.x + stroke_w, body.origin.y + stroke_w + inner_h - liquid_h,
                       body.size.w - stroke_w * 2, liquid_h);
    graphics_context_set_fill_color(ctx, liquid);
    graphics_fill_rect(ctx, fill, stroke_w, GCornersBottom);
  }

  graphics_context_set_stroke_color(ctx, stroke);
  graphics_draw_round_rect(ctx, body, stroke_w + 2);
}

void ui_draw_battery(GContext *ctx, GRect box, GColor stroke, int percent, bool charging) {
  int16_t nub_w = box.size.w >= 26 ? 3 : 2;
  GRect body = GRect(box.origin.x, box.origin.y, box.size.w - nub_w, box.size.h);
  GRect nub = GRect(body.origin.x + body.size.w, box.origin.y + box.size.h / 4, nub_w,
                    box.size.h - (box.size.h / 4) * 2);

  graphics_context_set_stroke_color(ctx, stroke);
  graphics_context_set_fill_color(ctx, stroke);
  graphics_context_set_stroke_width(ctx, 1);
  graphics_draw_round_rect(ctx, body, 2);
  graphics_fill_rect(ctx, nub, 1, GCornersRight);

  if (percent < 0) {
    return;
  }
  if (percent > 100) {
    percent = 100;
  }
  int16_t inner_w = body.size.w - 4;
  int16_t fill_w = inner_w * percent / 100;
  if (percent > 0 && fill_w < 1) {
    fill_w = 1;
  }
  graphics_fill_rect(ctx, GRect(body.origin.x + 2, body.origin.y + 2, fill_w, body.size.h - 4), 0,
                     GCornerNone);

  if (charging) {
    // A lightning bolt over the fill, drawn in the background colour so it shows.
    GPoint c = grect_center_point(&body);
    int16_t h = body.size.h - 4;
    GPathInfo bolt_info = {
      .num_points = 6,
      .points = (GPoint[]) {
        {c.x + 1, c.y - h / 2}, {c.x - 3, c.y + 1}, {c.x, c.y + 1},
        {c.x - 1, c.y + h / 2}, {c.x + 3, c.y - 1}, {c.x, c.y - 1},
      },
    };
    GPath *bolt = gpath_create(&bolt_info);
    graphics_context_set_fill_color(ctx, ui_state_color());
    gpath_draw_filled(ctx, bolt);
    graphics_context_set_stroke_color(ctx, ui_state_color());
    gpath_draw_outline(ctx, bolt);
    gpath_destroy(bolt);
  }
}
