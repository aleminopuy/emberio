#!/usr/bin/env python3
"""Draws the appstore icons and banner.

    python3 store/make_art.py

Writes store/icon-48.png, icon-80.png, icon-144.png and banner.png. The banner uses
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


def draw_mug(draw, x, y, s, line, body, steam):
    """An outlined mug with steam, filling an s-by-s box at (x, y). The body and the steam
    share a centre at x + MUG_CENTRE * s; the handle sticks out to the right of it."""
    def px(fx, fy):
        return (x + fx * s, y + fy * s)

    stroke = round(0.075 * s)
    # Handle first: the body, drawn over it, covers its left side.
    draw.rounded_rectangle([px(0.56, 0.47), px(0.86, 0.80)], radius=0.12 * s, outline=line, width=stroke)
    # Body: square rim, rounded base.
    draw.rounded_rectangle([px(0.14, 0.36), px(0.66, 0.94)], radius=0.10 * s, fill=body, outline=line,
                           width=stroke, corners=(False, False, True, True))
    # Steam: two wavy lines with round ends.
    for sx in (0.32, 0.48):
        points = [px(sx + 0.04 * math.sin(t / 12 * 2 * math.pi), 0.06 + 0.019 * t) for t in range(13)]
        draw.line(points, fill=steam, width=stroke, joint='curve')
        for p in (points[0], points[-1]):
            draw.ellipse([p[0] - stroke / 2, p[1] - stroke / 2, p[0] + stroke / 2, p[1] + stroke / 2], fill=steam)


MUG_CENTRE = 0.40  # the body's centre across the mug's box (its left side is at 0.14, right at 0.66)


def icon(size):
    # Drawn 8x larger for smooth edges. The background is the heating screen's orange and the steam
    # Rajah, both as a Pebble screen shows them (see ICON_BACKGROUND), so the icon matches the
    # screenshots. The mug's body and steam are centred; the handle sits off to the right.
    big = size * 8
    img = Image.new('RGB', (big, big), ICON_BACKGROUND)
    s = big * 0.72
    draw_mug(ImageDraw.Draw(img), big / 2 - MUG_CENTRE * s, big * 0.12, s, BLACK, WHITE, ICON_STEAM)
    return img.resize((size, size), Image.LANCZOS)


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
    if os.path.exists(os.path.join(HERE, 'screenshots', 'gabbro_1.png')):
        banner().save(os.path.join(HERE, 'banner.png'))
    else:
        print('No store/screenshots/gabbro_1.png yet; skipped the banner.')


if __name__ == '__main__':
    main()
