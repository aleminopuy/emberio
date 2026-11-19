#include <pebble.h>
#include "comm.h"
#include "menu_window.h"
#include "mug_window.h"
#include "state.h"

static void prv_on_update(void) {
  mug_window_refresh();
  menu_window_refresh();
}

#if defined(EMBERIO_DEMO)
// Canned states for emulator screenshots (EMBERIO_DEMO=<n> pebble build).
static void prv_load_demo(void) {
  MugState *s = state_get();
  s->link = LinkConnected;
  s->unit = UnitFahrenheit;
  s->temp_min = 1200;
  s->temp_max = 1450;
  s->alert_battery = 20;
  s->alert_ready = 1;
  s->level = 80;
  strncpy(s->name, "Ember Mug 2", sizeof(s->name) - 1);
  s->updated = (int32_t)time(NULL);
  switch (EMBERIO_DEMO) {
    case 1:  // heating up a fresh pour
      s->liquid = LiquidHeating;
      s->cur_temp = 1284;
      s->target_temp = 1400;
      s->battery = 72;
      s->charging = 0;
      s->minutes = 58;
      break;
    case 2:  // at temperature
      s->liquid = LiquidPerfect;
      s->cur_temp = 1351;
      s->target_temp = 1350;
      s->battery = 46;
      s->charging = 0;
      s->minutes = 37;
      break;
    case 3:  // on the coaster, Celsius
      s->unit = UnitCelsius;
      s->temp_min = 500;
      s->temp_max = 625;
      s->liquid = LiquidPerfect;
      s->cur_temp = 571;
      s->target_temp = 570;
      s->battery = 84;
      s->charging = 1;
      s->minutes = 14;
      break;
    case 4:  // lost the mug: last known readings
      s->link = LinkSearching;
      s->liquid = LiquidCooling;
      s->cur_temp = 1372;
      s->target_temp = 1350;
      s->battery = 18;
      s->charging = 0;
      s->minutes = 11;
      s->updated = (int32_t)time(NULL) - 14 * 60;
      break;
    default:  // never paired
      s->link = LinkNoMug;
      s->updated = 0;
      s->cur_temp = TEMP_UNKNOWN;
      s->target_temp = TEMP_UNKNOWN;
      s->battery = BATTERY_UNKNOWN;
      s->name[0] = '\0';
      break;
  }
  state_set_live(s->link == LinkConnected);
}
#endif

static void prv_init(void) {
  state_load();
#if defined(EMBERIO_DEMO)
  prv_load_demo();
#endif
#if defined(PBL_TOUCH)
  // Let swipes and taps drive the menus on touch watches.
  app_touch_navigation_enable(true);
#endif
  mug_window_push();
  comm_init(prv_on_update);
}

static void prv_deinit(void) {
  comm_deinit();
#if !defined(EMBERIO_DEMO)
  state_save();
#endif
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
