#include "mug_window.h"
#include "comm.h"
#include "menu_window.h"
#include "state.h"
#include "ui.h"

// After the last up/down press, wait this long before sending the new target,
// so holding the button doesn't flood the mug with writes.
#define COMMIT_DELAY_MS 1200
#define REPEAT_INTERVAL_MS 180

static Window *s_window;
static StatusBarLayer *s_status_bar;
static Layer *s_content;
static ActionBarLayer *s_action_bar;
static GBitmap *s_icon_plus;
static GBitmap *s_icon_minus;
static GBitmap *s_icon_more;

static int16_t s_pending_target = TEMP_UNKNOWN;
static AppTimer *s_commit_timer;

// ---------------------------------------------------------------------------------------------
// Text for the current state

static void prv_status_text(const char **title, const char **hint) {
  MugState *s = state_get();
  if (!connection_service_peek_pebble_app_connection()) {
    *title = "NO PHONE";
    *hint = "Your watch isn't connected to your phone.";
    return;
  }
  if (comm_phone_unreachable()) {
    *title = "NO REPLY";
    *hint = "Open the Emberio app on your phone.";
    return;
  }
  switch ((LinkStatus)s->link) {
    case LinkNoMug:
      *title = "NO MUG";
      *hint = "Pair your mug in the Emberio phone app.";
      return;
    case LinkSearching:
      *title = "SEARCHING";
      *hint = "Looking for your mug. Pick it up to wake it.";
      return;
    case LinkBluetoothOff:
      *title = "BLUETOOTH OFF";
      *hint = "Turn on Bluetooth on your phone.";
      return;
    case LinkNeedsSetup:
      *title = "SETUP NEEDED";
      *hint = "Open the Emberio app on your phone.";
      return;
    case LinkConnected:
    case LinkUnknown:
    default:
      *title = "CONNECTING";
      *hint = "Asking your phone...";
      return;
  }
}

// A shorter hint for under the last-seen readings.
static const char *prv_short_hint(void) {
  if (!connection_service_peek_pebble_app_connection()) {
    return "Watch not connected";
  }
  if (comm_phone_unreachable()) {
    return "Open Emberio on phone";
  }
  switch ((LinkStatus)state_get()->link) {
    case LinkNoMug: return "No mug paired";
    case LinkSearching: return "Lift mug to wake it";
    case LinkBluetoothOff: return "Phone Bluetooth is off";
    case LinkNeedsSetup: return "Open Emberio on phone";
    default: return "Connecting...";
  }
}

static bool prv_showing_pending(void) {
  return s_pending_target != TEMP_UNKNOWN && (s_commit_timer || comm_target_pending());
}

static void prv_target_line(char *buf, size_t size) {
  MugState *s = state_get();
  char temp[16];
  if (prv_showing_pending()) {
    state_format_temp(temp, sizeof(temp), s_pending_target, true);
    snprintf(buf, size, "Set to %s", temp);
    return;
  }
  if (s->target_temp == TEMP_UNKNOWN) {
    buf[0] = '\0';
    return;
  }
  if (s->target_temp == 0) {
    snprintf(buf, size, "Heater off");
    return;
  }
  state_format_temp(temp, sizeof(temp), s->target_temp, true);
  switch ((LiquidState)s->liquid) {
    case LiquidHeating: snprintf(buf, size, "Heating to %s", temp); break;
    case LiquidCooling: snprintf(buf, size, "Cooling to %s", temp); break;
    case LiquidPerfect: snprintf(buf, size, "Holding %s", temp); break;
    default: snprintf(buf, size, "Target %s", temp); break;
  }
}

static void prv_last_seen_line(char *buf, size_t size) {
  time_t when = (time_t)state_get()->updated;
  struct tm *tm = localtime(&when);
  char clock[12];
  strftime(clock, sizeof(clock), clock_is_24h_style() ? "%H:%M" : "%I:%M %p", tm);
  const char *trimmed = (clock[0] == '0') ? clock + 1 : clock;
  snprintf(buf, size, "Last seen %s", trimmed);
}

// ---------------------------------------------------------------------------------------------
// Drawing

// The big temperature with a drawn degree ring (the number fonts have no ° glyph).
static void prv_draw_big_temp(GContext *ctx, GRect box, int16_t tenths, GColor color) {
  char text[8];
  state_format_whole(text, sizeof(text), tenths);
  GFont font = (tenths == TEMP_UNKNOWN)
                   ? fonts_get_system_font(UI_LARGE ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_GOTHIC_24_BOLD)
                   : ui_font_big();
  GSize size = graphics_text_layout_get_content_size(text, font, box, GTextOverflowModeFill,
                                                     GTextAlignmentLeft);
  int16_t ring_r = UI_LARGE ? 6 : 4;
  int16_t ring_w = UI_LARGE ? 3 : 2;
  int16_t gap = UI_LARGE ? 3 : 2;
  int16_t total_w = size.w + gap + ring_r * 2;
  int16_t x = box.origin.x + (box.size.w - total_w) / 2;
  GRect text_box = GRect(x, box.origin.y, size.w + 4, box.size.h);
  graphics_context_set_text_color(ctx, color);
  graphics_draw_text(ctx, text, font, text_box, GTextOverflowModeFill, GTextAlignmentLeft, NULL);

  if (tenths != TEMP_UNKNOWN) {
    // LECO digits sit a few pixels below the top of their box.
    int16_t ring_y = box.origin.y + (UI_LARGE ? 14 : 10) + ring_r;
    graphics_context_set_stroke_color(ctx, color);
    graphics_context_set_stroke_width(ctx, ring_w);
    graphics_draw_circle(ctx, GPoint(x + size.w + gap + ring_r, ring_y), ring_r);
  }
}

static void prv_draw_label(GContext *ctx, GRect box, const char *text, GColor bg, GColor fg) {
#if defined(PBL_BW)
  // On black-and-white screens the state gets an inverted pill so it stands out.
  GSize size = graphics_text_layout_get_content_size(text, ui_font_label(), box,
                                                     GTextOverflowModeTrailingEllipsis,
                                                     GTextAlignmentCenter);
  GRect pill = GRect(box.origin.x + (box.size.w - size.w) / 2 - 6, box.origin.y + 2,
                     size.w + 12, box.size.h - 1);
  graphics_context_set_fill_color(ctx, fg);
  graphics_fill_rect(ctx, pill, 4, GCornersAll);
  ui_draw_text(ctx, text, ui_font_label(), box, GTextAlignmentCenter, bg);
#else
  ui_draw_text(ctx, text, ui_font_label(), box, GTextAlignmentCenter, fg);
#endif
}

static void prv_draw_target_line(GContext *ctx, GRect box, const char *text, GColor bg,
                                 GColor fg) {
  if (prv_showing_pending()) {
    // While adjusting, the new target is shown inverted, like a picker field.
    GSize size = graphics_text_layout_get_content_size(text, ui_font_line(), box,
                                                       GTextOverflowModeTrailingEllipsis,
                                                       GTextAlignmentCenter);
    GRect pill = GRect(box.origin.x + (box.size.w - size.w) / 2 - 6, box.origin.y + 3,
                       size.w + 12, box.size.h - 2);
    graphics_context_set_fill_color(ctx, fg);
    graphics_fill_rect(ctx, pill, 4, GCornersAll);
    ui_draw_text(ctx, text, ui_font_line(), box, GTextAlignmentCenter, bg);
    return;
  }
  ui_draw_text(ctx, text, ui_font_line(), box, GTextAlignmentCenter, fg);
}

#if defined(PBL_ROUND)
// On round screens the battery is an arc along the left edge, filling upwards.
static void prv_draw_battery_arc(GContext *ctx, GRect bounds, GColor fg) {
  MugState *s = state_get();
  int16_t thickness = UI_LARGE ? 8 : 6;
  GRect ring = grect_inset(bounds, GEdgeInsets(UI_LARGE ? 5 : 3));
  // From lower left to upper left. On Time Round's smaller screen it starts higher, clear of
  // the bottom line of text.
  int32_t start = DEG_TO_TRIGANGLE(UI_LARGE ? 212 : 232);
  int32_t end = DEG_TO_TRIGANGLE(318);
  // The empty part of the gauge: a dark track that shows on every state colour.
  graphics_context_set_fill_color(ctx, GColorDarkGray);
  graphics_fill_radial(ctx, ring, GOvalScaleModeFitCircle, thickness, start, end);
  if (s->battery != BATTERY_UNKNOWN) {
    int pct = s->battery > 100 ? 100 : s->battery;
    int32_t fill_end = start + (end - start) * pct / 100;
    graphics_context_set_fill_color(ctx, fg);
    if (fill_end > start) {
      graphics_fill_radial(ctx, ring, GOvalScaleModeFitCircle, thickness, start, fill_end);
    }
  }
}
#endif

static void prv_draw_battery_row(GContext *ctx, GRect box, GColor fg) {
  MugState *s = state_get();
  char pct[8];
  if (s->battery == BATTERY_UNKNOWN) {
    snprintf(pct, sizeof(pct), "--%%");
  } else {
    snprintf(pct, sizeof(pct), "%d%%", s->battery);
  }
  GFont font = ui_font_line();
  GSize text_size = graphics_text_layout_get_content_size(pct, font, box,
                                                          GTextOverflowModeFill,
                                                          GTextAlignmentLeft);
  int16_t glyph_w = UI_LARGE ? 30 : 22;
  int16_t glyph_h = UI_LARGE ? 15 : 11;
  int16_t gap = 5;
  int16_t total = glyph_w + gap + text_size.w;
  int16_t x = box.origin.x + (box.size.w - total) / 2;
  int16_t glyph_y = box.origin.y + (box.size.h - glyph_h) / 2 + (UI_LARGE ? 3 : 2);
  ui_draw_battery(ctx, GRect(x, glyph_y, glyph_w, glyph_h), fg,
                  s->battery == BATTERY_UNKNOWN ? -1 : s->battery, s->charging);
  graphics_context_set_text_color(ctx, fg);
  graphics_draw_text(ctx, pct, font, GRect(x + glyph_w + gap, box.origin.y, text_size.w + 4, box.size.h),
                     GTextOverflowModeFill, GTextAlignmentLeft, NULL);
}

static void prv_draw_message(GContext *ctx, GRect bounds, GColor fg) {
  const char *title;
  const char *hint;
  prv_status_text(&title, &hint);

  int16_t mug_w = UI_LARGE ? 54 : 40;
  int16_t mug_h = UI_LARGE ? 44 : 32;
  int16_t label_h = UI_LARGE ? 24 : 20;
  int16_t hint_w = bounds.size.w - PBL_IF_ROUND_ELSE(UI_LARGE ? 44 : 28, 16);
  GRect hint_box = GRect(bounds.origin.x + (bounds.size.w - hint_w) / 2, 0, hint_w, 200);
  GSize hint_size = graphics_text_layout_get_content_size(hint, ui_font_hint(), hint_box,
                                                          GTextOverflowModeWordWrap,
                                                          GTextAlignmentCenter);
  int16_t total = mug_h + 10 + label_h + hint_size.h;
  int16_t y = bounds.origin.y + (bounds.size.h - total) / 2;

  ui_draw_mug(ctx, GRect(bounds.origin.x + (bounds.size.w - mug_w) / 2 + mug_w / 10, y, mug_w, mug_h),
              fg, fg, 0);
  y += mug_h + 10;
  prv_draw_label(ctx, GRect(bounds.origin.x, y, bounds.size.w, label_h), title, ui_state_color(), fg);
  y += label_h;
  hint_box.origin.y = y - 2;
  hint_box.size.h = hint_size.h + 4;
  ui_draw_text(ctx, hint, ui_font_hint(), hint_box, GTextAlignmentCenter, fg);
}

static void prv_content_update(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  MugState *s = state_get();
  GColor bg = ui_state_color();
  GColor fg = ui_text_color();

  // No background fill here: the window paints it (and the round battery arc, which reaches
  // under this layer).
#if defined(PBL_COLOR)
  graphics_context_set_antialiased(ctx, true);
#endif

  bool live = state_is_live();
  if (!live && !state_has_readings()) {
    prv_draw_message(ctx, bounds, fg);
    return;
  }

  char line[32];
  char detail[32];
  const char *title;
  const char *hint;
  prv_status_text(&title, &hint);

  // The bottom line: time left when live, else a short hint (which may wrap), measured first so
  // the whole block can be centred around it.
  GFont detail_font = live ? ui_font_small()
                           : fonts_get_system_font(UI_LARGE ? FONT_KEY_GOTHIC_18 : FONT_KEY_GOTHIC_14);
  if (live) {
    state_format_battery_detail(detail, sizeof(detail));
  } else {
    snprintf(detail, sizeof(detail), "%s", prv_short_hint());
  }
  GRect detail_box = GRect(bounds.origin.x + 4, 0, bounds.size.w - 8, 60);
  int16_t detail_h = graphics_text_layout_get_content_size(detail, detail_font, detail_box,
                                                           GTextOverflowModeWordWrap,
                                                           GTextAlignmentCenter).h + 4;

  // Rows, top to bottom. Heights are the line boxes of each font.
  const int16_t label_h = UI_LARGE ? 24 : 18;
  const int16_t big_h = UI_LARGE ? 64 : 46;
  const int16_t line_h = UI_LARGE ? 30 : 23;
  const int16_t gap = UI_LARGE ? 8 : 5;
  const int16_t battery_h = UI_LARGE ? 30 : 23;
  int16_t total = label_h + big_h + line_h + gap + battery_h + detail_h;
  int16_t y = bounds.origin.y + (bounds.size.h - total) / 2;
#if defined(PBL_ROUND)
  // Keep clear of the curved bottom edge.
  y -= UI_LARGE ? 6 : 4;
#endif
  if (y < bounds.origin.y) {
    y = bounds.origin.y;
  }

  // State label
  prv_draw_label(ctx, GRect(bounds.origin.x, y, bounds.size.w, label_h), live ? state_liquid_label() : title,
                 bg, fg);
  y += label_h;

  // Temperature
  prv_draw_big_temp(ctx, GRect(bounds.origin.x, y - (UI_LARGE ? 6 : 4), bounds.size.w, big_h + 8),
                    s->cur_temp, fg);
  y += big_h;

  // Target, or when the data is old, how old
  if (live) {
    prv_target_line(line, sizeof(line));
  } else {
    prv_last_seen_line(line, sizeof(line));
  }
  prv_draw_target_line(ctx, GRect(bounds.origin.x, y, bounds.size.w, line_h), line, bg, fg);
  y += line_h + gap;

  // Battery
  prv_draw_battery_row(ctx, GRect(bounds.origin.x, y, bounds.size.w, battery_h), fg);
  y += battery_h;
  detail_box.origin.y = y - 2;
  detail_box.size.h = detail_h + 4;
  ui_draw_text(ctx, detail, detail_font, detail_box, GTextAlignmentCenter, fg);
}

static void prv_window_update(Layer *layer, GContext *ctx) {
  // Fill behind the status bar and the round action bar's curve with the state colour.
  graphics_context_set_fill_color(ctx, ui_state_color());
  graphics_fill_rect(ctx, layer_get_bounds(layer), 0, GCornerNone);
#if defined(PBL_ROUND)
  prv_draw_battery_arc(ctx, layer_get_bounds(layer), ui_text_color());
#endif
}

static void prv_apply_colors(void) {
  GColor bg = ui_state_color();
  status_bar_layer_set_colors(s_status_bar, bg, ui_text_color());
  window_set_background_color(s_window, bg);
}

// ---------------------------------------------------------------------------------------------
// Buttons

static void prv_commit(void *data) {
  s_commit_timer = NULL;
  if (s_pending_target != TEMP_UNKNOWN) {
    comm_set_target(s_pending_target);
  }
  mug_window_refresh();
}

static void prv_adjust(int direction) {
  MugState *s = state_get();
  if (!state_is_live()) {
    return;
  }
  int16_t step = state_temp_step();
  int16_t next;
  if (s_pending_target != TEMP_UNKNOWN && prv_showing_pending()) {
    next = s_pending_target + direction * step;
  } else if (s->target_temp <= 0 || s->target_temp == TEMP_UNKNOWN) {
    // Heater off: the first press shows where it would come back on.
    next = state_default_target();
  } else {
    int16_t base = s->target_temp;
    int16_t remainder = base % step;
    if (remainder != 0) {
      // Off the grid (e.g. 57.2°C set elsewhere): snap to the next step in that direction.
      next = direction > 0 ? base - remainder + step : base - remainder;
    } else {
      next = base + direction * step;
    }
  }
  if (next < state_temp_min()) {
    next = state_temp_min();
  }
  if (next > state_temp_max()) {
    next = state_temp_max();
  }
  s_pending_target = next;
  if (!s_commit_timer || !app_timer_reschedule(s_commit_timer, COMMIT_DELAY_MS)) {
    s_commit_timer = app_timer_register(COMMIT_DELAY_MS, prv_commit, NULL);
  }
  layer_mark_dirty(s_content);
}

static void prv_up_click(ClickRecognizerRef recognizer, void *context) {
  prv_adjust(+1);
}

static void prv_down_click(ClickRecognizerRef recognizer, void *context) {
  prv_adjust(-1);
}

static void prv_select_click(ClickRecognizerRef recognizer, void *context) {
  menu_window_push();
}

static void prv_click_config(void *context) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, REPEAT_INTERVAL_MS, prv_up_click);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, REPEAT_INTERVAL_MS, prv_down_click);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
}

// ---------------------------------------------------------------------------------------------
// Window

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  layer_set_update_proc(root, prv_window_update);

  s_icon_plus = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ACTION_PLUS);
  s_icon_minus = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ACTION_MINUS);
  s_icon_more = gbitmap_create_with_resource(RESOURCE_ID_IMAGE_ACTION_MORE);

  s_action_bar = action_bar_layer_create();
  action_bar_layer_set_background_color(s_action_bar, GColorBlack);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_UP, s_icon_plus);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_SELECT, s_icon_more);
  action_bar_layer_set_icon(s_action_bar, BUTTON_ID_DOWN, s_icon_minus);
  action_bar_layer_set_icon_press_animation(s_action_bar, BUTTON_ID_UP,
                                            ActionBarLayerIconPressAnimationMoveUp);
  action_bar_layer_set_icon_press_animation(s_action_bar, BUTTON_ID_DOWN,
                                            ActionBarLayerIconPressAnimationMoveDown);
  action_bar_layer_set_click_config_provider(s_action_bar, prv_click_config);

  int16_t content_w = bounds.size.w - ACTION_BAR_WIDTH;
  s_status_bar = status_bar_layer_create();
  status_bar_layer_set_separator_mode(s_status_bar, StatusBarLayerSeparatorModeNone);
  layer_set_frame(status_bar_layer_get_layer(s_status_bar),
                  GRect(0, 0, PBL_IF_ROUND_ELSE(bounds.size.w, content_w), STATUS_BAR_LAYER_HEIGHT));

#if defined(PBL_ROUND)
  // Leave room for the battery arc on the left.
  int16_t inset_left = UI_LARGE ? 22 : 14;
#else
  int16_t inset_left = 0;
#endif
  s_content = layer_create(GRect(inset_left, STATUS_BAR_LAYER_HEIGHT, content_w - inset_left,
                                 bounds.size.h - STATUS_BAR_LAYER_HEIGHT));
  layer_set_update_proc(s_content, prv_content_update);

  layer_add_child(root, s_content);
  layer_add_child(root, status_bar_layer_get_layer(s_status_bar));
  action_bar_layer_add_to_window(s_action_bar, window);
  prv_apply_colors();
}

static void prv_window_unload(Window *window) {
  if (s_commit_timer) {
    app_timer_cancel(s_commit_timer);
    s_commit_timer = NULL;
  }
  action_bar_layer_destroy(s_action_bar);
  status_bar_layer_destroy(s_status_bar);
  layer_destroy(s_content);
  gbitmap_destroy(s_icon_plus);
  gbitmap_destroy(s_icon_minus);
  gbitmap_destroy(s_icon_more);
  window_destroy(s_window);
  s_window = NULL;
}

void mug_window_refresh(void) {
  if (!s_window) {
    return;
  }
  // The mug confirmed (or we gave up): stop showing the value being set.
  if (!s_commit_timer && !comm_target_pending()) {
    s_pending_target = TEMP_UNKNOWN;
  }
  prv_apply_colors();
  layer_mark_dirty(window_get_root_layer(s_window));
  layer_mark_dirty(s_content);
}

void mug_window_set_target(int16_t tenths) {
  if (s_commit_timer) {
    app_timer_cancel(s_commit_timer);
    s_commit_timer = NULL;
  }
  s_pending_target = tenths;
  comm_set_target(tenths);
  mug_window_refresh();
}

void mug_window_heater_on(void) {
  if (s_commit_timer) {
    app_timer_cancel(s_commit_timer);
    s_commit_timer = NULL;
  }
  s_pending_target = state_default_target();
  comm_heater_on();
  mug_window_refresh();
}

void mug_window_push(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
}
