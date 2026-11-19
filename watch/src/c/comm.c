#include "comm.h"
#include "state.h"

// How long to wait for the phone to answer before suggesting the user open the phone app.
#define REPLY_TIMEOUT_MS 6000
// While the phone is unreachable, ask again this often.
#define RETRY_INTERVAL_MS 10000
// How long a target we sent can take to come back from the mug.
#define TARGET_TIMEOUT_MS 8000
#define SEND_RETRY_MS 750
#define MAX_SEND_ATTEMPTS 4
// s_target_to_send while turning the heater on: any target above 0 confirms it.
#define TARGET_ANY_ON (-1)

typedef enum {
  OutNone = 0,
  OutHello,
  OutTarget,
  OutHeaterOn,
  OutSettings,
} Outgoing;

static CommUpdateHandler s_on_update;

static bool s_need_hello;
static bool s_need_target;
static bool s_need_heater_on;
static bool s_need_settings;
static int16_t s_target_to_send;

static Outgoing s_in_flight = OutNone;
static int s_attempts;

static bool s_unreachable;
static bool s_target_pending;

static AppTimer *s_reply_timer;
static AppTimer *s_retry_timer;
static AppTimer *s_target_timer;
static AppTimer *s_flush_timer;

static void prv_flush(void);

static void prv_notify(void) {
  if (s_on_update) {
    s_on_update();
  }
}

static void prv_cancel(AppTimer **timer) {
  if (*timer) {
    app_timer_cancel(*timer);
    *timer = NULL;
  }
}

// Ints arrive in whatever width the phone chose; read them all the same way.
static int32_t prv_tuple_int(const Tuple *t) {
  if (t->type == TUPLE_INT) {
    switch (t->length) {
      case 1: return t->value->int8;
      case 2: return t->value->int16;
      default: return t->value->int32;
    }
  }
  if (t->type == TUPLE_UINT) {
    switch (t->length) {
      case 1: return t->value->uint8;
      case 2: return t->value->uint16;
      default: return (int32_t)t->value->uint32;
    }
  }
  return 0;
}

static void prv_retry_timer_fired(void *data) {
  s_retry_timer = NULL;
  comm_request_state();
}

static void prv_reply_timer_fired(void *data) {
  s_reply_timer = NULL;
  s_unreachable = true;
  prv_notify();
  prv_cancel(&s_retry_timer);
  s_retry_timer = app_timer_register(RETRY_INTERVAL_MS, prv_retry_timer_fired, NULL);
}

static void prv_target_timer_fired(void *data) {
  s_target_timer = NULL;
  s_target_pending = false;
  prv_notify();
}

static void prv_flush_timer_fired(void *data) {
  s_flush_timer = NULL;
  prv_flush();
}

static void prv_schedule_flush(uint32_t delay_ms) {
  if (!s_flush_timer) {
    s_flush_timer = app_timer_register(delay_ms, prv_flush_timer_fired, NULL);
  }
}

static int16_t prv_clamp16(int32_t value) {
  if (value > INT16_MAX) return INT16_MAX;
  if (value < INT16_MIN + 1) return INT16_MIN + 1;
  return (int16_t)value;
}

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
  MugState *s = state_get();
  bool got_reading = false;
  Tuple *t;

  if ((t = dict_find(iter, MESSAGE_KEY_STATUS))) {
    s->link = (uint8_t)prv_tuple_int(t);
  }
  if ((t = dict_find(iter, MESSAGE_KEY_UNIT))) {
    s->unit = prv_tuple_int(t) ? UnitFahrenheit : UnitCelsius;
  }
  if ((t = dict_find(iter, MESSAGE_KEY_TEMP_CUR))) {
    s->cur_temp = prv_clamp16(prv_tuple_int(t));
    got_reading = true;
  }
  if ((t = dict_find(iter, MESSAGE_KEY_TEMP_TARGET))) {
    s->target_temp = prv_clamp16(prv_tuple_int(t));
    // Only the target we asked for (or, for "heater on", any target) settles a change;
    // a routine update sent before the mug confirmed still carries the old one.
    bool settled = s_target_to_send == TARGET_ANY_ON ? s->target_temp > 0
                                                     : s->target_temp == s_target_to_send;
    if (s_target_pending && settled) {
      s_target_pending = false;
      prv_cancel(&s_target_timer);
    }
  }
  if ((t = dict_find(iter, MESSAGE_KEY_TEMP_MIN))) {
    s->temp_min = prv_clamp16(prv_tuple_int(t));
  }
  if ((t = dict_find(iter, MESSAGE_KEY_TEMP_MAX))) {
    s->temp_max = prv_clamp16(prv_tuple_int(t));
  }
  if ((t = dict_find(iter, MESSAGE_KEY_LIQUID_STATE))) {
    s->liquid = (uint8_t)prv_tuple_int(t);
  }
  if ((t = dict_find(iter, MESSAGE_KEY_LIQUID_LEVEL))) {
    s->level = (uint8_t)prv_tuple_int(t);
  }
  if ((t = dict_find(iter, MESSAGE_KEY_BATTERY))) {
    s->battery = (uint8_t)prv_tuple_int(t);
  }
  if ((t = dict_find(iter, MESSAGE_KEY_CHARGING))) {
    s->charging = prv_tuple_int(t) ? 1 : 0;
  }
  if ((t = dict_find(iter, MESSAGE_KEY_MINUTES_LEFT))) {
    s->minutes = prv_clamp16(prv_tuple_int(t));
  }
  if ((t = dict_find(iter, MESSAGE_KEY_MUG_NAME)) && t->type == TUPLE_CSTRING) {
    strncpy(s->name, t->value->cstring, sizeof(s->name) - 1);
    s->name[sizeof(s->name) - 1] = '\0';
  }
  if ((t = dict_find(iter, MESSAGE_KEY_ALERT_BATTERY))) {
    s->alert_battery = (uint8_t)prv_tuple_int(t);
  }
  if ((t = dict_find(iter, MESSAGE_KEY_ALERT_READY))) {
    s->alert_ready = prv_tuple_int(t) ? 1 : 0;
  }
  if ((t = dict_find(iter, MESSAGE_KEY_UNIT_PREF))) {
    s->unit_pref = (uint8_t)prv_tuple_int(t);
  }

  if (s->link == LinkConnected) {
    if (got_reading) {
      s->updated = (int32_t)time(NULL);
      state_set_live(true);
    }
  } else {
    state_set_live(false);
  }

  // Anything from the phone means it's there.
  s_unreachable = false;
  prv_cancel(&s_reply_timer);
  prv_cancel(&s_retry_timer);
  prv_notify();
}

static void prv_requeue(Outgoing what) {
  switch (what) {
    case OutHello: s_need_hello = true; break;
    case OutTarget: s_need_target = true; break;
    case OutHeaterOn: s_need_heater_on = true; break;
    case OutSettings: s_need_settings = true; break;
    case OutNone: break;
  }
}

static void prv_outbox_sent(DictionaryIterator *iter, void *context) {
  s_in_flight = OutNone;
  s_attempts = 0;
  prv_flush();
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "send failed: %d", (int)reason);
  Outgoing failed = s_in_flight;
  s_in_flight = OutNone;
  if (++s_attempts < MAX_SEND_ATTEMPTS) {
    prv_requeue(failed);
    prv_schedule_flush(SEND_RETRY_MS);
    return;
  }
  // Give up on this one; the reply timer (or the next button press) tries again.
  s_attempts = 0;
  if (failed == OutTarget || failed == OutHeaterOn) {
    s_target_pending = false;
    prv_cancel(&s_target_timer);
  }
  s_unreachable = true;
  prv_notify();
  prv_schedule_flush(SEND_RETRY_MS);
}

static void prv_flush(void) {
  if (s_in_flight != OutNone) {
    return;
  }
  Outgoing next = s_need_target ? OutTarget
                : s_need_heater_on ? OutHeaterOn
                : s_need_settings ? OutSettings
                : s_need_hello ? OutHello
                : OutNone;
  if (next == OutNone) {
    return;
  }

  DictionaryIterator *iter;
  AppMessageResult result = app_message_outbox_begin(&iter);
  if (result != APP_MSG_OK || !iter) {
    prv_schedule_flush(SEND_RETRY_MS);
    return;
  }

  MugState *s = state_get();
  dict_write_uint8(iter, MESSAGE_KEY_PROTOCOL, PROTOCOL_VERSION);
  switch (next) {
    case OutTarget:
      s_need_target = false;
      dict_write_uint8(iter, MESSAGE_KEY_CMD, CmdSetTarget);
      dict_write_int32(iter, MESSAGE_KEY_TEMP_TARGET, s_target_to_send);
      dict_write_uint8(iter, MESSAGE_KEY_UNIT, s->unit);
      break;
    case OutHeaterOn:
      s_need_heater_on = false;
      dict_write_uint8(iter, MESSAGE_KEY_CMD, CmdHeaterOn);
      break;
    case OutSettings:
      s_need_settings = false;
      dict_write_uint8(iter, MESSAGE_KEY_CMD, CmdSetSettings);
      dict_write_uint8(iter, MESSAGE_KEY_ALERT_BATTERY, s->alert_battery);
      dict_write_uint8(iter, MESSAGE_KEY_ALERT_READY, s->alert_ready);
      dict_write_uint8(iter, MESSAGE_KEY_UNIT_PREF, s->unit_pref);
      break;
    case OutHello:
      s_need_hello = false;
      dict_write_uint8(iter, MESSAGE_KEY_CMD, CmdHello);
      break;
    case OutNone:
      break;
  }

  s_in_flight = next;
  if (app_message_outbox_send() != APP_MSG_OK) {
    s_in_flight = OutNone;
    prv_requeue(next);
    prv_schedule_flush(SEND_RETRY_MS);
  }
}

static void prv_start_reply_timer(void) {
  prv_cancel(&s_reply_timer);
  s_reply_timer = app_timer_register(REPLY_TIMEOUT_MS, prv_reply_timer_fired, NULL);
}

void comm_request_state(void) {
  s_need_hello = true;
  if (!s_reply_timer) {
    prv_start_reply_timer();
  }
  prv_flush();
}

void comm_set_target(int16_t tenths) {
#if defined(EMBERIO_DEMO)
  state_get()->target_temp = tenths;
  prv_notify();
  return;
#endif
  s_target_to_send = tenths;
  s_need_target = true;
  s_need_heater_on = false;
  s_target_pending = true;
  prv_cancel(&s_target_timer);
  s_target_timer = app_timer_register(TARGET_TIMEOUT_MS, prv_target_timer_fired, NULL);
  if (!s_reply_timer) {
    prv_start_reply_timer();
  }
  prv_flush();
}

void comm_heater_on(void) {
#if defined(EMBERIO_DEMO)
  state_get()->target_temp = state_default_target();
  prv_notify();
  return;
#endif
  s_need_heater_on = true;
  s_need_target = false;
  s_target_to_send = TARGET_ANY_ON;
  s_target_pending = true;
  prv_cancel(&s_target_timer);
  s_target_timer = app_timer_register(TARGET_TIMEOUT_MS, prv_target_timer_fired, NULL);
  prv_flush();
}

void comm_send_settings(void) {
#if defined(EMBERIO_DEMO)
  return;
#endif
  s_need_settings = true;
  prv_flush();
}

bool comm_phone_unreachable(void) {
  return s_unreachable;
}

bool comm_target_pending(void) {
  return s_target_pending;
}

static void prv_app_connection_changed(bool connected) {
  if (connected) {
    comm_request_state();
  } else {
    state_set_live(false);
    prv_notify();
  }
}

void comm_init(CommUpdateHandler on_update) {
  s_on_update = on_update;
#if defined(EMBERIO_DEMO)
  return;
#endif
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_outbox_sent(prv_outbox_sent);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_open(256, 128);
  connection_service_subscribe((ConnectionHandlers) {
    .pebble_app_connection_handler = prv_app_connection_changed,
  });
  comm_request_state();
}

void comm_deinit(void) {
  prv_cancel(&s_reply_timer);
  prv_cancel(&s_retry_timer);
  prv_cancel(&s_target_timer);
  prv_cancel(&s_flush_timer);
#if !defined(EMBERIO_DEMO)
  connection_service_unsubscribe();
  app_message_deregister_callbacks();
#endif
}
