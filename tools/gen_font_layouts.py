#!/usr/bin/env python3
"""Generates one widget layout per clock font from res/layout/widget.xml.

App widgets cannot change a TextView's typeface at runtime, so each font option needs its own
layout. Only the main cluster (time, temperature, C/F mark) uses android:fontFamily="sans-serif"
in widget.xml; those attributes are swapped for the chosen family.

Usage: python3 tools/gen_font_layouts.py   (re-run after editing widget.xml)
"""
import os

LAYOUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'layout')
# key -> system font family (must match ClockFont in Settings.kt)
FONTS = {
    'light': 'sans-serif-light',
    'thin': 'sans-serif-thin',
    'medium': 'sans-serif-medium',
    'black': 'sans-serif-black',
    'condensed': 'sans-serif-condensed',
    'serif': 'serif',
    'mono': 'monospace',
}

src = open(os.path.join(LAYOUT, 'widget.xml')).read()
assert src.count('android:fontFamily="sans-serif"') == 3, 'expected 3 main-cluster fontFamily attributes'
header = '<?xml version="1.0" encoding="utf-8"?>\n'
for key, family in FONTS.items():
    out = src.replace('android:fontFamily="sans-serif"', 'android:fontFamily="%s"' % family)
    out = out.replace(header, header + '<!-- Generated from widget.xml by tools/gen_font_layouts.py - do not edit by hand. -->\n', 1)
    open(os.path.join(LAYOUT, 'widget_font_%s.xml' % key), 'w').write(out)
print('generated %d layouts' % len(FONTS))
