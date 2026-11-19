// Runs the plugin script in a sandbox with a fake Pebble host and a fake phone app.
// node --test test/*.test.cjs
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SCRIPT = fs.readFileSync(path.join(__dirname, '../src/plugin/index.js'), 'utf8');

function load(fetchImpl) {
  let handler;
  const logs = [];
  const context = {
    console: { log: (m) => logs.push(m) },
    Date,
    Math,
    fetch: fetchImpl,
    Pebble: { registerSourceHandler: (h) => (handler = h) },
  };
  vm.createContext(context);
  vm.runInContext(SCRIPT, context);
  const read = (request) =>
    new Promise((resolve) =>
      handler(request, {
        data: (d) => resolve(JSON.parse(JSON.stringify(d))),
        error: (code, message) => resolve({ code, message }),
      }),
    );
  return { read, logs };
}

function serving(body, status = 200) {
  return async (url) => {
    assert.strictEqual(url, 'http://127.0.0.1:47811/v1/state');
    return { ok: status === 200, status, json: async () => body };
  };
}

const HEATING = {
  status: 'connected',
  mug: {
    name: 'Ember Mug 2',
    unit: 'F',
    temperature: 128.44,
    target: 140,
    targetRange: { min: 120, max: 145 },
    state: 'heating',
    battery: 72,
    charging: false,
    minutesLeft: 58,
    liquidLevel: 80,
    updatedAt: 1795000000,
  },
};

test('a live mug serves every reading in every shape it declares', async () => {
  const { read } = load(serving(HEATING));
  const envelope = await read({ category: 'drink', item: 'mug' });
  assert.ok(envelope.validUntilMs > Date.now());
  assert.strictEqual(envelope.instances.length, 1);
  const p = envelope.instances[0].properties;
  assert.strictEqual(envelope.instances[0].instanceId, 'mug');
  assert.deepStrictEqual(p.name, { shortText: { text: 'Mug' }, longText: { text: 'Ember Mug 2' } });
  assert.deepStrictEqual(p.temperature.shortText, { text: '128°F' });
  assert.deepStrictEqual(p.temperature.numericValue, { value: 128.4, unit: '°F' });
  assert.deepStrictEqual(p.target_temperature.numericValue, { value: 140, unit: '°F', min: 120, max: 145 });
  assert.deepStrictEqual(p.state, { shortText: { text: 'Heating' }, longText: { text: 'Heating to 140°F' } });
  assert.deepStrictEqual(p.battery_level.numericValue, { value: 72, unit: '%', min: 0, max: 100 });
  assert.strictEqual(p.battery_level.shortText.text, '72%');
  assert.deepStrictEqual(p.battery_time_left, {
    shortText: { text: '58m' },
    longText: { text: '58 min left' },
    numericValue: { value: 58, unit: 'min' },
  });
  assert.deepStrictEqual(p.charging, { boolean: { value: false }, shortText: { text: 'Off base' } });
  assert.deepStrictEqual(p.liquid_level.numericValue, { value: 80, unit: '%', min: 0, max: 100 });
  assert.deepStrictEqual(p.updated, { timestamp: { value: 1795000000 } });
});

test('short texts stay within about 7 characters', async () => {
  const { read } = load(serving({ ...HEATING, mug: { ...HEATING.mug, minutesLeft: 125 } }));
  const p = (await read({ category: 'drink', item: 'mug' })).instances[0].properties;
  for (const [name, shapes] of Object.entries(p)) {
    if (shapes.shortText) assert.ok(shapes.shortText.text.length <= 8, `${name}: ${shapes.shortText.text}`);
  }
  assert.strictEqual(p.battery_time_left.shortText.text, '2h05');
  assert.strictEqual(p.battery_time_left.longText.text, '2 h 5 min left');
});

test('celsius keeps half degrees; charging shows time to full', async () => {
  const body = {
    status: 'connected',
    mug: { name: 'Mug', unit: 'C', temperature: 57.3, target: 57.5, state: 'perfect', battery: 84, charging: true, minutesLeft: 14 },
  };
  const p = (await load(serving(body)).read({ category: 'drink', item: 'mug' })).instances[0].properties;
  assert.strictEqual(p.temperature.shortText.text, '57.5°C');
  assert.strictEqual(p.target_temperature.longText.text, '57.5°C');
  assert.strictEqual(p.state.longText.text, 'Perfect');
  assert.strictEqual(p.battery_time_left.shortText.text, 'Full 14m');
  assert.strictEqual(p.battery_time_left.longText.text, 'Full in 14 min');
  assert.strictEqual(p.battery_level.longText.text, '84%, charging');
  assert.strictEqual(p.name.shortText.text, 'Mug');
});

test('heater off is a state, not a temperature', async () => {
  const body = { status: 'connected', mug: { ...HEATING.mug, target: 0, state: 'cold' } };
  const p = (await load(serving(body)).read({ category: 'drink', item: 'mug' })).instances[0].properties;
  assert.deepStrictEqual(p.target_temperature, { shortText: { text: 'Off' }, longText: { text: 'Heater off' } });
  assert.strictEqual(p.state.shortText.text, 'Off');
  assert.strictEqual(p.target_temperature.numericValue, undefined);
});

test('a disconnected mug serves only its name and why', async () => {
  const body = { status: 'searching', mug: { name: 'Ember Mug 2', temperature: 120 } };
  const p = (await load(serving(body)).read({ category: 'drink', item: 'mug' })).instances[0].properties;
  assert.deepStrictEqual(Object.keys(p).sort(), ['name', 'state']);
  assert.strictEqual(p.state.shortText.text, 'Asleep');
});

test('unknown readings are left out rather than guessed', async () => {
  const body = { status: 'connected', mug: { name: 'Ember Cup', unit: 'F', state: 'empty', battery: null, minutesLeft: -1 } };
  const p = (await load(serving(body)).read({ category: 'drink', item: 'mug' })).instances[0].properties;
  assert.strictEqual(p.temperature, undefined);
  assert.strictEqual(p.battery_level, undefined);
  assert.strictEqual(p.battery_time_left, undefined);
  assert.strictEqual(p.state.shortText.text, 'Empty');
});

test('no phone app: unavailable; a refused permission says so; other sources are refused', async () => {
  const down = load(async () => { throw new TypeError('Failed to fetch'); });
  assert.strictEqual((await down.read({ category: 'drink', item: 'mug' })).code, 'PLUGIN_UNAVAILABLE');

  const blocked = load(async () => {
    const e = new Error('blocked');
    e.name = 'NotAllowedError';
    throw e;
  });
  assert.strictEqual((await blocked.read({ category: 'drink', item: 'mug' })).code, 'PERMISSION_DENIED');

  const httpError = load(serving({}, 503));
  assert.strictEqual((await httpError.read({ category: 'drink', item: 'mug' })).code, 'PLUGIN_UNAVAILABLE');

  assert.strictEqual((await load(serving(HEATING)).read({ category: 'weather', item: 'location' })).code, 'INVALID_REQUEST');
});
