#pragma once
#include <pebble.h>

// What the phone app last told us about its link to the mug (message key STATUS).
typedef enum {
  LinkUnknown = 0,       // nothing heard from the phone yet
  LinkNoMug = 1,         // no mug paired in the phone app
  LinkSearching = 2,     // paired, looking for / connecting to the mug
  LinkConnected = 3,     // live readings
  LinkBluetoothOff = 4,  // phone Bluetooth is off
  LinkNeedsSetup = 5,    // phone app needs attention (permissions etc.)
} LinkStatus;

// The mug's own liquid state codes (Ember characteristic 0x0008).
typedef enum {
  LiquidStandby = 0,
  LiquidEmpty = 1,
  LiquidFilling = 2,
  LiquidCold = 3,
  LiquidCooling = 4,
  LiquidHeating = 5,
  LiquidPerfect = 6,
  LiquidWarm = 7,
} LiquidState;

typedef enum {
  UnitCelsius = 0,
  UnitFahrenheit = 1,
} TempUnit;

typedef enum {
  UnitPrefMug = 0,  // follow the unit set on the mug
  UnitPrefCelsius = 1,
  UnitPrefFahrenheit = 2,
} UnitPref;

#define TEMP_UNKNOWN INT16_MIN
#define BATTERY_UNKNOWN 255
#define MINUTES_UNKNOWN (-1)

// Temperatures are tenths of a degree in the display unit the phone chose (UNIT).
// A target of 0 means the heater is off.
typedef struct {
  uint8_t version;
  uint8_t link;           // LinkStatus
  uint8_t unit;           // TempUnit
  uint8_t liquid;         // LiquidState
  uint8_t battery;        // percent, BATTERY_UNKNOWN if not known
  uint8_t charging;       // on the charging coaster
  uint8_t level;          // liquid level, percent
  uint8_t alert_battery;  // low battery alert threshold in percent, 0 = off
  uint8_t alert_ready;    // notify when the drink reaches the target
  uint8_t unit_pref;      // UnitPref
  int16_t cur_temp;
  int16_t target_temp;
  int16_t temp_min;
  int16_t temp_max;
  int16_t minutes;        // battery minutes left (or to full when charging), -1 unknown
  int32_t updated;        // time() of the last live reading, 0 = never
  char name[24];
} MugState;

MugState *state_get(void);
void state_load(void);
void state_save(void);

// True when the readings came from a live connection, not the cache.
bool state_is_live(void);
void state_set_live(bool live);
// True when we have readings at all (live or cached).
bool state_has_readings(void);

int16_t state_temp_min(void);
int16_t state_temp_max(void);
// One press of up/down: 1°F, or 0.5°C.
int16_t state_temp_step(void);
// The target to start from when turning the heater on: the last one, else 135°F / 57°C.
int16_t state_default_target(void);

// "134", rounded to a whole degree.
void state_format_whole(char *buf, size_t size, int16_t tenths);
// "140°F", or "57.5°C" (keeps a half degree in Celsius).
void state_format_temp(char *buf, size_t size, int16_t tenths, bool with_unit);
// "58 min left", "Full in 25 min", ...
void state_format_battery_detail(char *buf, size_t size);
// "HEATING", "PERFECT", ...
const char *state_liquid_label(void);
const char *state_unit_suffix(void);
