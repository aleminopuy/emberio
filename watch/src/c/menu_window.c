#include "menu_window.h"
#include "comm.h"
#include "mug_window.h"
#include "state.h"
#include "ui.h"

typedef enum {
  SectionTemperature = 0,
  SectionAlerts,
  SectionDisplay,
  SectionAbout,
  SectionCount,
} Section;

typedef enum {
  RowLatte = 0,
  RowCoffee,
  RowTea,
  RowHeater,
  TemperatureRowCount,
} TemperatureRow;

typedef enum {
  RowLowBattery = 0,
  RowReady,
  AlertRowCount,
} AlertRow;

typedef struct {
  const char *name;
  int16_t fahrenheit;  // tenths
  int16_t celsius;     // tenths
} Preset;

// Presets like Mug Monitor's: a latte drinks cooler than coffee, tea hotter.
static const Preset PRESETS[] = {
  {"Latte", 1300, 545},
  {"Coffee", 1350, 570},
  {"Tea", 1400, 600},
};

static const uint8_t BATTERY_THRESHOLDS[] = {0, 10, 15, 20, 25, 30, 40, 50};

static Window *s_window;
static MenuLayer *s_menu;
static char s_subtitle[32];

static int16_t prv_preset_value(int index) {
  return state_get()->unit == UnitFahrenheit ? PRESETS[index].fahrenheit : PRESETS[index].celsius;
}

static uint16_t prv_num_sections(MenuLayer *menu, void *context) {
  return SectionCount;
}

static uint16_t prv_num_rows(MenuLayer *menu, uint16_t section, void *context) {
  switch ((Section)section) {
    case SectionTemperature: return TemperatureRowCount;
    case SectionAlerts: return AlertRowCount;
    case SectionDisplay: return 1;
    case SectionAbout: return 1;
    default: return 0;
  }
}

static int16_t prv_header_height(MenuLayer *menu, uint16_t section, void *context) {
  return PBL_IF_ROUND_ELSE(0, MENU_CELL_BASIC_HEADER_HEIGHT);
}

static void prv_draw_header(GContext *ctx, const Layer *cell, uint16_t section, void *context) {
#if !defined(PBL_ROUND)
  static const char *titles[] = {"Temperature", "Alerts", "Display", "About"};
  menu_cell_basic_header_draw(ctx, cell, titles[section]);
#endif
}

#if defined(PBL_ROUND)
static int16_t prv_cell_height(MenuLayer *menu, MenuIndex *index, void *context) {
  return menu_layer_is_index_selected(menu, index) ? MENU_CELL_ROUND_FOCUSED_TALL_CELL_HEIGHT
                                                   : MENU_CELL_ROUND_UNFOCUSED_SHORT_CELL_HEIGHT;
}
#endif

static void prv_draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *context) {
  MugState *s = state_get();
  const char *title = "";
  s_subtitle[0] = '\0';

  switch ((Section)index->section) {
    case SectionTemperature:
      if (index->row < RowHeater) {
        title = PRESETS[index->row].name;
        state_format_temp(s_subtitle, sizeof(s_subtitle), prv_preset_value(index->row), true);
      } else if (s->target_temp == 0) {
        title = "Heater on";
        char temp[16];
        state_format_temp(temp, sizeof(temp), state_default_target(), true);
        snprintf(s_subtitle, sizeof(s_subtitle), "Back to %s", temp);
      } else {
        title = "Heater off";
        snprintf(s_subtitle, sizeof(s_subtitle), "Let it cool, save battery");
      }
      break;
    case SectionAlerts:
      if (index->row == RowLowBattery) {
        title = "Low battery";
        if (s->alert_battery == 0) {
          snprintf(s_subtitle, sizeof(s_subtitle), "Off");
        } else {
          snprintf(s_subtitle, sizeof(s_subtitle), "At %d%%", s->alert_battery);
        }
      } else {
        title = "Drink ready";
        snprintf(s_subtitle, sizeof(s_subtitle), "%s", s->alert_ready ? "On" : "Off");
      }
      break;
    case SectionDisplay:
      title = "Units";
      switch ((UnitPref)s->unit_pref) {
        case UnitPrefCelsius: snprintf(s_subtitle, sizeof(s_subtitle), "Celsius"); break;
        case UnitPrefFahrenheit: snprintf(s_subtitle, sizeof(s_subtitle), "Fahrenheit"); break;
        default:
          snprintf(s_subtitle, sizeof(s_subtitle), "Same as mug (%s)", state_unit_suffix());
          break;
      }
      break;
    case SectionAbout:
      title = "Emberio 1.0";
      snprintf(s_subtitle, sizeof(s_subtitle), "%s", s->name[0] ? s->name : "No mug yet");
      break;
    default:
      break;
  }
  menu_cell_basic_draw(ctx, cell, title, s_subtitle[0] ? s_subtitle : NULL, NULL);
}

static void prv_select(MenuLayer *menu, MenuIndex *index, void *context) {
  MugState *s = state_get();
  switch ((Section)index->section) {
    case SectionTemperature:
      if (!state_is_live()) {
        vibes_short_pulse();
        return;
      }
      if (index->row < RowHeater) {
        mug_window_set_target(prv_preset_value(index->row));
      } else if (s->target_temp == 0) {
        mug_window_heater_on();
      } else {
        mug_window_set_target(0);
      }
      window_stack_pop(true);
      return;
    case SectionAlerts:
      if (index->row == RowLowBattery) {
        size_t count = ARRAY_LENGTH(BATTERY_THRESHOLDS);
        size_t next = 0;
        for (size_t i = 0; i < count; i++) {
          if (BATTERY_THRESHOLDS[i] == s->alert_battery) {
            next = (i + 1) % count;
            break;
          }
        }
        s->alert_battery = BATTERY_THRESHOLDS[next];
      } else {
        s->alert_ready = !s->alert_ready;
      }
      comm_send_settings();
      break;
    case SectionDisplay:
      s->unit_pref = (s->unit_pref + 1) % 3;
      comm_send_settings();
      break;
    case SectionAbout:
    default:
      break;
  }
  menu_layer_reload_data(menu);
}

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  s_menu = menu_layer_create(bounds);
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_sections = prv_num_sections,
    .get_num_rows = prv_num_rows,
    .get_header_height = prv_header_height,
    .draw_header = prv_draw_header,
    .draw_row = prv_draw_row,
    .select_click = prv_select,
#if defined(PBL_ROUND)
    .get_cell_height = prv_cell_height,
#endif
  });
#if defined(PBL_COLOR)
  menu_layer_set_highlight_colors(s_menu, ui_accent_color(), GColorWhite);
#endif
  menu_layer_set_click_config_onto_window(s_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void prv_window_unload(Window *window) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
  window_destroy(s_window);
  s_window = NULL;
}

void menu_window_refresh(void) {
  if (s_menu) {
    menu_layer_reload_data(s_menu);
  }
}

void menu_window_push(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
}
