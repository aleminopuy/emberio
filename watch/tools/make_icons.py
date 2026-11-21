#!/usr/bin/env python3
"""Draws the watchapp's icons pixel by pixel, so they stay crisp on 1-bit screens.

Run from the watch/ directory: python3 tools/make_icons.py
Writes resources/images/*.png (committed, so building doesn't need Pillow).
"""
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), '..', 'resources', 'images')
WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)
CLEAR = (0, 0, 0, 0)
GREY_DARK = (85, 85, 85, 255)  # the two greys Pebble's own icons use
GREY_LIGHT = (170, 170, 170, 255)


def canvas(w, h):
    img = Image.new('RGBA', (w, h), CLEAR)
    return img, ImageDraw.Draw(img)


def save(img, name):
    img.save(os.path.join(OUT, name))


def action_plus():
    img, d = canvas(17, 17)
    d.rectangle([2, 7, 14, 9], fill=WHITE)
    d.rectangle([7, 2, 9, 14], fill=WHITE)
    save(img, 'action_plus.png')


def action_minus():
    img, d = canvas(17, 17)
    d.rectangle([2, 7, 14, 9], fill=WHITE)
    save(img, 'action_minus.png')


def action_more():
    img, d = canvas(17, 17)
    for x in (1, 7, 13):
        d.rectangle([x, 7, x + 2, 9], fill=WHITE)
    save(img, 'action_more.png')


def menu_icon():
    """A steaming mug in outline, 25x25, drawn like Pebble's own launcher icons: 2px black
    strokes, hollow inside, with grey pixels smoothing the curves. The launcher tints it to suit
    the row (black, or the highlighted row's text colour), as it does its own icons.

    menu_icon.png is for colour watches; menu_icon~bw.png, without the greys, for black and white.
    """
    ss = 16  # drawn 16x larger, then scaled down: the coverage of each pixel gives the greys
    big = Image.new('L', (25 * ss, 25 * ss), 0)
    d = ImageDraw.Draw(big)
    stroke = 2 * ss

    def box(x0, y0, x1, y1):
        # Pixel edges -> the big image's inclusive pixel box.
        return [x0 * ss, y0 * ss, x1 * ss - 1, y1 * ss - 1]

    # Handle: a loop whose left side runs inside the body's right side.
    d.rounded_rectangle(box(16, 12, 23, 21), radius=3 * ss, outline=255, width=stroke)
    # Body: square rim, rounded base.
    d.rounded_rectangle(box(2, 9, 18, 24), radius=3 * ss, outline=255, width=stroke,
                        corners=(False, False, True, True))
    cover = big.resize((25, 25), Image.BOX)
    color, _ = canvas(25, 25)
    bw, _ = canvas(25, 25)
    for y in range(25):
        for x in range(25):
            c = cover.getpixel((x, y)) / 255
            if c >= 0.72:
                color.putpixel((x, y), BLACK)
            elif c >= 0.38:
                color.putpixel((x, y), GREY_DARK)
            elif c >= 0.15:
                color.putpixel((x, y), GREY_LIGHT)
            if c >= 0.5:
                bw.putpixel((x, y), BLACK)
    # Steam: two 2px wavy wisps over the middle of the mug, placed by hand (too small to smooth).
    for left in (6, 11):
        for y, shift in enumerate((1, 0, 0, 1, 1, 0), start=1):
            for x in (left + shift, left + shift + 1):
                color.putpixel((x, y), BLACK)
                bw.putpixel((x, y), BLACK)
    save(color, 'menu_icon.png')
    save(bw, 'menu_icon~bw.png')


def main():
    os.makedirs(OUT, exist_ok=True)
    action_plus()
    action_minus()
    action_more()
    menu_icon()


if __name__ == '__main__':
    main()
