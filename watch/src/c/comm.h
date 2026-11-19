#pragma once
#include <pebble.h>

// Commands the watch sends to the phone (message key CMD).
typedef enum {
  CmdHello = 1,        // send me everything
  CmdSetTarget = 2,    // TEMP_TARGET, tenths of the display unit; 0 turns the heater off
  CmdSetSettings = 3,  // ALERT_BATTERY, ALERT_READY, UNIT_PREF
  CmdHeaterOn = 4,     // turn the heater back on at the last target
} Command;

#define PROTOCOL_VERSION 1

typedef void (*CommUpdateHandler)(void);

void comm_init(CommUpdateHandler on_update);
void comm_deinit(void);

void comm_request_state(void);
void comm_set_target(int16_t tenths);
void comm_heater_on(void);
void comm_send_settings(void);

// True when the phone hasn't answered for a while: show "open the phone app".
bool comm_phone_unreachable(void);
// True while a target we sent hasn't come back from the mug yet.
bool comm_target_pending(void);
