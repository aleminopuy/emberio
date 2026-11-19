// Emberio watchface plugin.
//
// Serves `drink/mug`: the Ember mug's temperature, target, state, battery and time left, so any
// plugin-aware watchface (or the Pebble app's Complications) can show them.
//
// The mug talks Bluetooth to the Emberio Android app, not to the Pebble app, so this script reads
// the readings that app serves on the phone itself (127.0.0.1 only; reaching it needs the
// LocalNetwork permission). A plugin only runs while something reads from it, and the host polls
// it every `suggestedRefreshIntervalSec`.

var STATE_URL = 'http://127.0.0.1:47811/v1/state';
var INSTANCE_ID = 'mug';
var REFRESH_MS = 30 * 1000;

var STATE_SHORT = {
  heating: 'Heating',
  cooling: 'Cooling',
  perfect: 'Perfect',
  empty: 'Empty',
  filling: 'Filling',
  standby: 'Standby',
  cold: 'Cold',
  warm: 'Warm',
  off: 'Off',
};

var STATUS_SHORT = {
  searching: 'Asleep',
  no_mug: 'No mug',
  bluetooth_off: 'BT off',
  needs_setup: 'Setup',
};

function shortText(text) {
  return { shortText: { text: String(text) } };
}

function texts(short, long) {
  return { shortText: { text: String(short) }, longText: { text: String(long) } };
}

function isNumber(value) {
  return typeof value === 'number' && isFinite(value);
}

function unitSymbol(unit) {
  return unit === 'C' ? '°C' : '°F';
}

/** 134.6 -> "135°F"; Celsius keeps a half degree: 57.5 -> "57.5°C". */
function formatTemp(value, unit) {
  var rounded = unit === 'C' ? Math.round(value * 2) / 2 : Math.round(value);
  return String(rounded) + unitSymbol(unit);
}

/** 58 -> "58m" / "58 min"; 65 -> "1h05" / "1 h 5 min". */
function formatDuration(minutes) {
  var m = Math.max(0, Math.round(minutes));
  if (m < 60) return { short: m + 'm', long: m + ' min' };
  var h = Math.floor(m / 60);
  var rest = m % 60;
  return {
    short: h + 'h' + (rest < 10 ? '0' : '') + rest,
    long: h + ' h' + (rest ? ' ' + rest + ' min' : ''),
  };
}

function temperatureProperty(value, unit, range) {
  var text = formatTemp(value, unit);
  var shapes = texts(text, text);
  shapes.numericValue = { value: Math.round(value * 10) / 10, unit: unitSymbol(unit) };
  if (range && isNumber(range.min) && isNumber(range.max) && range.max > range.min) {
    shapes.numericValue.min = range.min;
    shapes.numericValue.max = range.max;
  }
  return shapes;
}

/** The phone app's JSON -> the plugin envelope's one instance. Exported for tests. */
function buildInstance(body) {
  var mug = (body && body.mug) || {};
  var status = (body && body.status) || 'searching';
  var properties = {};
  var name = mug.name || 'Ember mug';
  properties.name = texts(name.length <= 7 ? name : 'Mug', name);

  if (status !== 'connected') {
    var reason = STATUS_SHORT[status] || 'Offline';
    properties.state = texts(reason, name + ': ' + reason.toLowerCase());
    return { instanceId: INSTANCE_ID, properties: properties };
  }

  var unit = mug.unit === 'C' ? 'C' : 'F';
  if (isNumber(mug.temperature)) {
    properties.temperature = temperatureProperty(mug.temperature, unit);
  }
  var heaterOff = mug.target === 0;
  if (isNumber(mug.target) && !heaterOff) {
    properties.target_temperature = temperatureProperty(mug.target, unit, mug.targetRange);
  } else if (heaterOff) {
    properties.target_temperature = texts('Off', 'Heater off');
  }

  var stateKey = heaterOff && mug.state !== 'empty' ? 'off' : mug.state;
  var stateShort = STATE_SHORT[stateKey];
  if (stateShort) {
    var stateLong = stateShort;
    if (!heaterOff && isNumber(mug.target) && (stateKey === 'heating' || stateKey === 'cooling')) {
      stateLong = stateShort + ' to ' + formatTemp(mug.target, unit);
    } else if (stateKey === 'off') {
      stateLong = 'Heater off';
    }
    properties.state = texts(stateShort, stateLong);
  }

  if (isNumber(mug.battery)) {
    var pct = Math.max(0, Math.min(100, Math.round(mug.battery)));
    properties.battery_level = texts(pct + '%', pct + '%' + (mug.charging ? ', charging' : ''));
    properties.battery_level.numericValue = { value: pct, unit: '%', min: 0, max: 100 };
  }

  if (typeof mug.charging === 'boolean') {
    properties.charging = { boolean: { value: mug.charging } };
    properties.charging.shortText = { text: mug.charging ? 'On base' : 'Off base' };
  }

  if (isNumber(mug.minutesLeft) && mug.minutesLeft >= 0) {
    var d = formatDuration(mug.minutesLeft);
    if (mug.charging) {
      properties.battery_time_left = texts('Full ' + d.short, 'Full in ' + d.long);
    } else {
      properties.battery_time_left = texts(d.short, d.long + ' left');
    }
    properties.battery_time_left.numericValue = { value: Math.round(mug.minutesLeft), unit: 'min' };
  }

  if (isNumber(mug.liquidLevel)) {
    var level = Math.max(0, Math.min(100, Math.round(mug.liquidLevel)));
    properties.liquid_level = shortText(level + '%');
    properties.liquid_level.numericValue = { value: level, unit: '%', min: 0, max: 100 };
  }

  if (isNumber(mug.updatedAt)) {
    properties.updated = { timestamp: { value: Math.floor(mug.updatedAt) } };
  }

  return { instanceId: INSTANCE_ID, properties: properties };
}

async function readState() {
  var response = await fetch(STATE_URL, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error('HTTP ' + response.status);
  }
  return response.json();
}

Pebble.registerSourceHandler(async function (request, respond) {
  if (request.category !== 'drink' || request.item !== 'mug') {
    respond.error('INVALID_REQUEST', 'Emberio only serves drink/mug.');
    return;
  }
  var body;
  try {
    body = await readState();
  } catch (e) {
    if (e && e.name === 'NotAllowedError') {
      respond.error('PERMISSION_DENIED', 'Allow Emberio to use your local network in the Pebble app.');
    } else {
      console.log('[Emberio] phone app unreachable: ' + (e && e.message));
      respond.error('PLUGIN_UNAVAILABLE', 'Open the Emberio app on your phone.');
    }
    return;
  }
  respond.data({
    validUntilMs: Date.now() + REFRESH_MS,
    instances: [buildInstance(body)],
  });
});
