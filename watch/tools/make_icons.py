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
    # A mug with steam, 25x25, black on transparent like Pebble's own launcher icons.
    img, d = canvas(25, 25)
    # Steam: two short S-curves.
    for x in (8, 13):
        d.point([(x, 1), (x + 1, 2), (x + 1, 3), (x, 4), (x, 5), (x + 1, 6)], fill=BLACK)
    # Body: rounded at the bottom.
    d.rectangle([3, 9, 18, 21], fill=BLACK)
    d.point([(3, 21), (18, 21)], fill=CLEAR)
    d.rectangle([4, 22, 17, 23], fill=BLACK)
    d.point([(4, 23), (17, 23)], fill=CLEAR)
    # Handle: a 2px loop on the right.
    d.rectangle([19, 11, 22, 12], fill=BLACK)
    d.rectangle([21, 11, 22, 18], fill=BLACK)
    d.rectangle([19, 17, 22, 18], fill=BLACK)
    # Rim highlight so it reads as a mug, not a box.
    d.line([5, 11, 16, 11], fill=CLEAR)
    save(img, 'menu_icon.png')


def main():
    os.makedirs(OUT, exist_ok=True)
    action_plus()
    action_minus()
    action_more()
    menu_icon()


if __name__ == '__main__':
    main()
