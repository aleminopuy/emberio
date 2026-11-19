#include "state.h"

#define PERSIST_KEY_STATE 1
#define STATE_VERSION 1

static MugState s_state;
static bool s_live;

MugState *state_get(void) {
  return &s_state;
}

static void prv_defaults(void) {
  memset(&s_state, 0, sizeof(s_state));
  s_state.version = STATE_VERSION;
  s_state.link = LinkUnknown;
  s_state.unit = UnitFahrenheit;
  s_state.battery = BATTERY_UNKNOWN;
  s_state.alert_battery = 20;
  s_state.alert_ready = 1;
  s_state.unit_pref = UnitPrefMug;
  s_state.cur_temp = TEMP_UNKNOWN;
  s_state.target_temp = TEMP_UNKNOWN;
  s_state.minutes = MINUTES_UNKNOWN;
}

void state_load(void) {
  prv_defaults();
  MugState stored;
  if (persist_exists(PERSIST_KEY_STATE) &&
      persist_read_data(PERSIST_KEY_STATE, &stored, sizeof(stored)) == (int)sizeof(stored) &&
      stored.version == STATE_VERSION) {
    s_state = stored;
    s_state.name[sizeof(s_state.name) - 1] = '\0';
  }
  // Whatever we stored is history until the phone says otherwise.
  s_state.link = LinkUnknown;
  s_live = false;
}

void state_save(void) {
  persist_write_data(PERSIST_KEY_STATE, &s_state, sizeof(s_state));
}

void state_set_live(bool live) {
  s_live = live;
}

bool state_is_live(void) {
  return s_live && s_state.link == LinkConnected;
}

bool state_has_readings(void) {
  return s_state.updated != 0 && s_state.cur_temp != TEMP_UNKNOWN;
}

int16_t state_temp_min(void) {
  if (s_state.temp_min > 0) {
    return s_state.temp_min;
  }
  return s_state.unit == UnitFahrenheit ? 1200 : 500;
}

int16_t state_temp_max(void) {
  if (s_state.temp_max > s_state.temp_min && s_state.temp_max > 0) {
    return s_state.temp_max;
  }
  return s_state.unit == UnitFahrenheit ? 1450 : 625;
}

int16_t state_temp_step(void) {
  return s_state.unit == UnitFahrenheit ? 10 : 5;
}

int16_t state_default_target(void) {
  if (s_state.target_temp != TEMP_UNKNOWN && s_state.target_temp > 0) {
    return s_state.target_temp;
  }
  return s_state.unit == UnitFahrenheit ? 1350 : 570;
}

void state_format_whole(char *buf, size_t size, int16_t tenths) {
  if (tenths == TEMP_UNKNOWN) {
    snprintf(buf, size, "--");
    return;
  }
  int value = tenths;
  bool negative = value < 0;
  if (negative) {
    value = -value;
  }
  snprintf(buf, size, "%s%d", negative ? "-" : "", (value + 5) / 10);
}

const char *state_unit_suffix(void) {
  return s_state.unit == UnitFahrenheit ? "°F" : "°C";
}

void state_format_temp(char *buf, size_t size, int16_t tenths, bool with_unit) {
  const char *suffix = with_unit ? state_unit_suffix() : "°";
  if (tenths == TEMP_UNKNOWN) {
    snprintf(buf, size, "--%s", suffix);
    return;
  }
  int value = tenths < 0 ? -tenths : tenths;
  const char *sign = tenths < 0 ? "-" : "";
  if (s_state.unit == UnitCelsius && value % 10 != 0) {
    snprintf(buf, size, "%s%d.%d%s", sign, value / 10, value % 10, suffix);
  } else {
    snprintf(buf, size, "%s%d%s", sign, (value + 5) / 10, suffix);
  }
}

static void prv_format_duration(char *buf, size_t size, int minutes) {
  if (minutes >= 60) {
    snprintf(buf, size, "%dh %02dm", minutes / 60, minutes % 60);
  } else {
    snprintf(buf, size, "%d min", minutes);
  }
}

void state_format_battery_detail(char *buf, size_t size) {
  char duration[16];
  if (s_state.battery == BATTERY_UNKNOWN) {
    snprintf(buf, size, "Battery unknown");
  } else if (s_state.charging) {
    if (s_state.battery >= 100) {
      snprintf(buf, size, "Fully charged");
    } else if (s_state.minutes > 0) {
      prv_format_duration(duration, sizeof(duration), s_state.minutes);
      snprintf(buf, size, "Full in %s", duration);
    } else {
      snprintf(buf, size, "Charging");
    }
  } else if (s_state.minutes >= 0) {
    prv_format_duration(duration, sizeof(duration), s_state.minutes);
    snprintf(buf, size, "%s left", duration);
  } else {
    snprintf(buf, size, "Estimating...");
  }
}

const char *state_liquid_label(void) {
  switch ((LiquidState)s_state.liquid) {
    case LiquidStandby: return "STANDBY";
    case LiquidEmpty: return "EMPTY";
    case LiquidFilling: return "FILLING";
    case LiquidCold: return "COLD";
    case LiquidCooling: return "COOLING";
    case LiquidHeating: return "HEATING";
    case LiquidPerfect: return "PERFECT";
    case LiquidWarm: return "WARM";
  }
  return "";
}
