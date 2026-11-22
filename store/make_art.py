#!/usr/bin/env python3
"""Draws the appstore icons and banner, and the Android app's launcher icon to match.

    python3 store/make_art.py

Writes store/icon-48.png, icon-80.png, icon-144.png and banner.png, and the Android launcher
icon's layers (android/app/src/main/res: drawable/ic_launcher_foreground.xml,
drawable/ic_launcher_monochrome.xml and the background colour in values/colors.xml). The banner uses
store/screenshots/gabbro_1.png, so take the screenshots first
(watch/tools/store-screenshots.sh). Needs Pillow and the Inter font.
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ORANGE = (255, 85, 0)
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
# The icon's colours as a Pebble screen shows them: `pebble screenshot` corrects the palette's
# Orange (#FF5500) to #E66E6B and Rajah (#FFAA55) to #F1AD93, so these match the screenshots.
ICON_BACKGROUND = (0xE6, 0x6E, 0x6B)
ICON_STEAM = (0xF1, 0xAD, 0x93)
FONT_DIR = '/usr/share/fonts/opentype/inter'
SUPERSAMPLE = 4


def font(name, size):
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)


# The mug, in fractions of its square box: (left, top, right, bottom) boxes and corner radii.
MUG_HANDLE, MUG_HANDLE_RADIUS = (0.56, 0.47, 0.86, 0.80), 0.12
MUG_BODY, MUG_BODY_RADIUS = (0.14, 0.36, 0.66, 0.94), 0.10  # square rim, rounded base
MUG_STROKE = 0.075  # outline and steam width
MUG_CENTRE = 0.40  # the body's (and the steam's) centre across the box
# The mug's box on a square icon of side 1: 0.72 wide, 0.12 from the top, body centred across.
MUG_SIZE, MUG_TOP = 0.72, 0.12


def steam_points(sx):
    """One wisp of steam: a sine wave rising over the mug, as (x, y) fractions of the box."""
    return [(sx + 0.04 * math.sin(t / 12 * 2 * math.pi), 0.06 + 0.019 * t) for t in range(13)]


STEAM_X = (0.32, 0.48)


def draw_mug(draw, x, y, s, line, body, steam):
    """An outlined mug with steam, filling an s-by-s box at (x, y). The body and the steam
    share a centre at x + MUG_CENTRE * s; the handle sticks out to the right of it."""
    def px(fx, fy):
        return (x + fx * s, y + fy * s)

    stroke = round(MUG_STROKE * s)
    # Handle first: the body, drawn over it, covers its left side.
    hx0, hy0, hx1, hy1 = MUG_HANDLE
    draw.rounded_rectangle([px(hx0, hy0), px(hx1, hy1)], radius=MUG_HANDLE_RADIUS * s, outline=line,
                           width=stroke)
    bx0, by0, bx1, by1 = MUG_BODY
    draw.rounded_rectangle([px(bx0, by0), px(bx1, by1)], radius=MUG_BODY_RADIUS * s, fill=body, outline=line,
                           width=stroke, corners=(False, False, True, True))
    # Steam: two wavy lines with round ends.
    for sx in STEAM_X:
        points = [px(fx, fy) for fx, fy in steam_points(sx)]
        draw.line(points, fill=steam, width=stroke, joint='curve')
        for p in (points[0], points[-1]):
            draw.ellipse([p[0] - stroke / 2, p[1] - stroke / 2, p[0] + stroke / 2, p[1] + stroke / 2], fill=steam)


def icon(size):
    # Drawn 8x larger for smooth edges. The background is the heating screen's orange and the steam
    # Rajah, both as a Pebble screen shows them (see ICON_BACKGROUND), so the icon matches the
    # screenshots. The mug's body and steam are centred; the handle sits off to the right.
    big = size * 8
    img = Image.new('RGB', (big, big), ICON_BACKGROUND)
    s = big * MUG_SIZE
    draw_mug(ImageDraw.Draw(img), big / 2 - MUG_CENTRE * s, big * MUG_TOP, s, BLACK, WHITE, ICON_STEAM)
    return img.resize((size, size), Image.LANCZOS)


# ------------------------------------------------------------------ Android launcher icon
#
# An adaptive icon: a 108dp canvas of which launchers show the middle 72dp, cut to their own shape
# (circle, squircle...). The store icon's square is laid over that middle 72dp, so the two match,
# and the whole mug stays inside the 66dp circle every shape keeps. Vector drawables, so Android
# draws them sharp at every screen density.

ANDROID_RES = os.path.join(HERE, '..', 'android', 'app', 'src', 'main', 'res')


def _n(v):
    return ('%.3f' % v).rstrip('0').rstrip('.')


def _hex(rgb):
    return '#FF%02X%02X%02X' % rgb


def _android_mug():
    """Path data for the mug on the 108dp canvas: handle ring, body outer and inner, steam."""
    tile, offset = 72.0, 18.0
    s = tile * MUG_SIZE
    x, y = offset + tile / 2 - MUG_CENTRE * s, offset + tile * MUG_TOP
    stroke = MUG_STROKE * s

    def rect(box, radius, inset=0.0, corners=(True, True, True, True)):
        x0, y0 = x + box[0] * s + inset, y + box[1] * s + inset
        x1, y1 = x + box[2] * s - inset, y + box[3] * s - inset
        tl, tr, br, bl = (max(radius * s - inset, 0.0) if c else 0.0 for c in corners)

        def arc(r, ex, ey):
            return 'A%s,%s 0,0 1 %s,%s ' % (_n(r), _n(r), _n(ex), _n(ey)) if r else ''

        return ('M%s,%s H%s ' % (_n(x0 + tl), _n(y0), _n(x1 - tr)) + arc(tr, x1, y0 + tr) +
                'V%s ' % _n(y1 - br) + arc(br, x1 - br, y1) +
                'H%s ' % _n(x0 + bl) + arc(bl, x0, y1 - bl) +
                'V%s ' % _n(y0 + tl) + arc(tl, x0 + tl, y0) + 'Z')

    base = (False, False, True, True)
    steam = [' '.join(('M' if i == 0 else 'L') + '%s,%s' % (_n(x + fx * s), _n(y + fy * s))
                      for i, (fx, fy) in enumerate(steam_points(sx))) for sx in STEAM_X]
    return {
        'handle_ring': rect(MUG_HANDLE, MUG_HANDLE_RADIUS) + ' ' + rect(MUG_HANDLE, MUG_HANDLE_RADIUS, stroke),
        'body_outer': rect(MUG_BODY, MUG_BODY_RADIUS, corners=base),
        'body_inner': rect(MUG_BODY, MUG_BODY_RADIUS, stroke, corners=base),
        # The body's inside, from its right edge leftwards: the monochrome icon hides the handle there.
        'body_inside_right': x + MUG_BODY[2] * s - stroke,
        'steam': steam,
        'stroke': stroke,
    }


def _vector(paths):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Drawn by store/make_art.py; edit that and run it again rather than editing this. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n' + paths + '</vector>\n')


def _fill(data, color, even_odd=False, indent='    '):
    rule = '\n%s    android:fillType="evenOdd"' % indent if even_odd else ''
    return ('%s<path\n%s    android:fillColor="%s"%s\n%s    android:pathData="%s" />\n'
            % (indent, indent, color, rule, indent, data))


def _stroke(data, color, width):
    return ('    <path\n'
            '        android:strokeColor="%s"\n'
            '        android:strokeWidth="%s"\n'
            '        android:strokeLineCap="round"\n'
            '        android:strokeLineJoin="round"\n'
            '        android:pathData="%s" />\n' % (color, _n(width), data))


def android_icon():
    """Writes the launcher icon's foreground (the store icon's mug) and monochrome layer (the same
    mug in outline, which Android 13+ tints for themed icons), and its background colour."""
    m = _android_mug()
    black, white, steam = _hex(BLACK), _hex(WHITE), _hex(ICON_STEAM)

    foreground = (_fill(m['handle_ring'], black, even_odd=True) +
                  _fill(m['body_outer'], black) +
                  _fill(m['body_inner'], white) +
                  ''.join(_stroke(p, steam, m['stroke']) for p in m['steam']))
    # Monochrome: outlines only. The body is hollow here, so the handle's left side, which the white
    # body covers in colour, is clipped off.
    clip = 'M%s,0 H108 V108 H%s Z' % (_n(m['body_inside_right']), _n(m['body_inside_right']))
    monochrome = ('    <group>\n'
                  '        <clip-path android:pathData="%s" />\n' % clip +
                  _fill(m['handle_ring'], white, even_odd=True, indent='        ') +
                  '    </group>\n' +
                  _fill(m['body_outer'] + ' ' + m['body_inner'], white, even_odd=True) +
                  ''.join(_stroke(p, white, m['stroke']) for p in m['steam']))

    drawable = os.path.join(ANDROID_RES, 'drawable')
    with open(os.path.join(drawable, 'ic_launcher_foreground.xml'), 'w') as f:
        f.write(_vector(foreground))
    with open(os.path.join(drawable, 'ic_launcher_monochrome.xml'), 'w') as f:
        f.write(_vector(monochrome))
    with open(os.path.join(ANDROID_RES, 'values', 'colors.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <!-- The store icon\'s background: the heating screen\'s orange as a Pebble shows it. -->\n'
                '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n'
                % ICON_BACKGROUND)


def banner():
    w, h = 720, 320
    img = Image.new('RGB', (w, h))
    # A warm dark gradient with an orange glow behind the watch.
    pixels = img.load()
    for yy in range(h):
        for xx in range(w):
            t = xx / w
            glow = max(0.0, 1 - math.hypot(xx - 570, yy - 160) / 260)
            r = int(24 + 40 * t + 150 * glow ** 2)
            g = int(14 + 10 * t + 45 * glow ** 2)
            b = int(10 + 4 * t)
            pixels[xx, yy] = (min(r, 255), min(g, 255), min(b, 255))
    draw = ImageDraw.Draw(img)

    # Words.
    draw.text((40, 70), 'Emberio', font=font('InterDisplay-Bold.otf', 66), fill=WHITE)
    draw.text((42, 152), 'Your Ember mug on your Pebble', font=font('Inter-SemiBold.otf', 24), fill=(255, 170, 120))
    small = font('Inter-Medium.otf', 18)
    for i, line in enumerate(('Temperature and minutes of heat left', 'Set the temperature from your wrist',
                              'A buzz when your drink is ready')):
        draw.ellipse([44, 204 + i * 28, 52, 212 + i * 28], fill=ORANGE)
        draw.text((62, 197 + i * 28), line, font=small, fill=(235, 225, 215))

    # The Round 2 screenshot, cut to its circle, with a soft shadow.
    shot = Image.open(os.path.join(HERE, 'screenshots', 'gabbro_1.png')).convert('RGB')
    size = 260
    mask = Image.new('L', (size * SUPERSAMPLE, size * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * SUPERSAMPLE - 1, size * SUPERSAMPLE - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    left, top = 440, (h - size) // 2
    shadow = Image.new('L', (w, h), 0)
    ImageDraw.Draw(shadow).ellipse([left - 6, top + 2, left + size + 6, top + size + 10], fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(10))
    img.paste((0, 0, 0), (0, 0), shadow)
    bezel = 8
    ImageDraw.Draw(img).ellipse([left - bezel, top - bezel, left + size + bezel, top + size + bezel], fill=(20, 20, 22))
    img.paste(shot, (left, top), mask)
    return img


def main():
    for size in (48, 80, 144):
        icon(size).save(os.path.join(HERE, 'icon-%d.png' % size))
    android_icon()
    if os.path.exists(os.path.join(HERE, 'screenshots', 'gabbro_1.png')):
        banner().save(os.path.join(HERE, 'banner.png'))
    else:
        print('No store/screenshots/gabbro_1.png yet; skipped the banner.')


if __name__ == '__main__':
    main()
