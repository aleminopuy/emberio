# Appstore listing

Everything to paste into the Pebble appstore (Core's Developer Dashboard) and the Rebble Dev
Portal. Both take plain text. Images are in this folder (see the bottom).

## Name

Emberio

## Category

Tools & Utilities

## Description (Pebble appstore, for Core's Pebble app)

```
Your Ember mug on your Pebble.

See your drink's temperature and how many minutes of heat your mug's battery has left, change the temperature from your wrist, and get a buzz when your drink is ready or the battery runs low.

• The drink's temperature, big, on a background that shows what the mug is doing: orange heating, green perfect, blue cooling
• Target temperature, battery percentage, and minutes of heat left (or time to full on the coaster)
• Up/Down change the target. Select opens Latte, Coffee and Tea presets, heater on/off, alerts and units
• Alerts on your watch: drink ready, and low battery at the level you choose
• Watchface plugin: plugin-aware watchfaces and Complications can show your drink's temperature, battery and time left
• Every Pebble, with round layouts for Time Round and Round 2

You need:
• An Android phone with the free Emberio companion app, which talks to your mug over Bluetooth: https://github.com/aleminopuy/emberio/releases/latest
• An Ember Mug 2 (the Mug, Cup, Tumbler and Travel Mug should work too)

iPhone isn't supported.

Unofficial. Not made by, or affiliated with, Ember Technologies.

Open source (MIT): https://github.com/aleminopuy/emberio
```

## Description (Rebble appstore)

```
Your Ember mug on your Pebble.

See your drink's temperature and how many minutes of heat your mug's battery has left, change the temperature from your wrist, and get a buzz when your drink is ready or the battery runs low.

• The drink's temperature, big, on a background that shows what the mug is doing: orange heating, green perfect, blue cooling
• Target temperature, battery percentage, and minutes of heat left (or time to full on the coaster)
• Up/Down change the target. Select opens Latte, Coffee and Tea presets, heater on/off, alerts and units
• Alerts on your watch: drink ready, and low battery at the level you choose
• Every Pebble, with round layouts for Time Round and Round 2

You need:
• An Android phone with the free Emberio companion app, which talks to your mug over Bluetooth: https://github.com/aleminopuy/emberio/releases/latest
• The original Pebble app, Gadgetbridge, or Core's Pebble app
• An Ember Mug 2 (the Mug, Cup, Tumbler and Travel Mug should work too)

Support for the original Pebble app and Gadgetbridge is new; if Emberio doesn't connect for you, please open an issue on GitHub. The watchface plugin needs Core's Pebble app. iPhone isn't supported.

Unofficial. Not made by, or affiliated with, Ember Technologies.

Open source (MIT): https://github.com/aleminopuy/emberio
```

## Release notes (1.1.0)

```
First appstore release.
• Also works with the original Pebble app and Gadgetbridge, as well as Core's Pebble app
• The menu's About row shows the version
```

## Links

| Field | Value |
|---|---|
| Website | https://github.com/aleminopuy/emberio |
| Source code | https://github.com/aleminopuy/emberio |
| Support / issues | https://github.com/aleminopuy/emberio/issues |

**Companion app** (Core's dashboard): the field wants a Google Play link, and Emberio isn't on
Google Play, so leave it empty. The watchapp already tells the Pebble app where to get the
companion (GitHub releases), and the description links it.

## Images

| What | File | Size |
|---|---|---|
| Small icon | `icon-80.png` | 80×80 |
| Large icon | `icon-144.png` | 144×144 |
| Banner (same one for every platform) | `banner.png` | 720×320 |
| Screenshots, 5 per platform | `screenshots/<platform>_<n>.png` | the display's own size |

Screenshots per platform, in order: heating, perfect, charging (°C), the menu, the alert
settings. Platforms: aplite (Pebble, Steel), basalt (Time, Time Steel), chalk (Time Round),
diorite (Pebble 2), emery (Time 2), flint (Pebble 2 Duo), gabbro (Round 2).

Regenerate with `watch/tools/store-screenshots.sh`, then `python3 store/make_art.py`.
