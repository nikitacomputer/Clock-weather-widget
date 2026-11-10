#!/usr/bin/env python3
"""Generates the weather icon set as Android VectorDrawables.

Each icon is a list of layers painted back-to-front. Every layer is a sampled
polygon (or open polyline) with an optional fill colour and a black outline.

* Colour variant (wx_<name>.xml): fills + black outlines, painted in order.
* Outline variant (wx_<name>_outline.xml): no fills, and every outline is
  clipped where a later (front) layer covers it, so hidden lines never show.

Usage: python3 tools/gen_icons.py [--svg preview.svg]
"""
import math, os, sys

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'drawable')
STROKE = '#FF1A1A1A'
SW = 2.2
YELLOW = '#FFF2C14E'; MOON = '#FFF5D97A'; CLOUD = '#FFEEEBE5'; DARK = '#FFA3A8AE'
BLUE = '#FF3B8FDB'; ICE = '#FFD6E9F8'; GREY = '#FFC9C9C9'; RED = '#FFE05252'
ORANGE = '#FFE8915A'; WHITE = '#FFFFFFFF'; BLACK = '#FF1A1A1A'
STEP = 0.35  # sampling resolution in viewport units


class Layer:
    def __init__(self, pts, fill=None, closed=True, solid=None):
        self.pts, self.fill, self.closed = pts, fill, closed
        # does this layer hide what is behind it?
        self.solid = closed if solid is None else solid
        self.keep_fill_in_outline = False


# ---------- geometry helpers ----------
def arc(cx, cy, r, a0, a1):
    n = max(2, int(abs(a1 - a0) * r / STEP))
    return [(cx + r * math.cos(a0 + (a1 - a0) * i / n), cy + r * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + 1)]

def circle(cx, cy, r):
    return arc(cx, cy, r, 0, 2 * math.pi)[:-1]

def seg(p, q):
    d = math.dist(p, q); n = max(1, int(d / STEP))
    return [(p[0] + (q[0] - p[0]) * i / n, p[1] + (q[1] - p[1]) * i / n) for i in range(n + 1)]

def thick(center, w):
    """Outline polygon of a polyline drawn with half-width w and round caps."""
    c = dedupe(center)
    def normal(i):
        a = c[max(i - 1, 0)]; b = c[min(i + 1, len(c) - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]; l = math.hypot(dx, dy)
        return (-dy / l, dx / l)
    left = []; right = []
    for i, p in enumerate(c):
        n = normal(i)
        left.append((p[0] + n[0] * w, p[1] + n[1] * w)); right.append((p[0] - n[0] * w, p[1] - n[1] * w))
    n_end = normal(len(c) - 1); a_end = math.atan2(n_end[1], n_end[0])
    n_st = normal(0); a_st = math.atan2(n_st[1], n_st[0])
    cap_end = arc(c[-1][0], c[-1][1], w, a_end, a_end - math.pi)
    cap_st = arc(c[0][0], c[0][1], w, a_st + math.pi, a_st)
    return left + cap_end[1:-1] + right[::-1] + cap_st[1:-1]

def pill(p, q, w):
    return thick(seg(p, q), w)

def dedupe(pts):
    out = [pts[0]]
    for p in pts[1:]:
        if math.dist(p, out[-1]) > 1e-6: out.append(p)
    return out

def circ_intersect(c1, r1, c2, r2):
    d = math.dist(c1, c2)
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d); h = math.sqrt(max(r1 * r1 - a * a, 0))
    mx = c1[0] + a * (c2[0] - c1[0]) / d; my = c1[1] + a * (c2[1] - c1[1]) / d
    p1 = (mx + h * (c2[1] - c1[1]) / d, my - h * (c2[0] - c1[0]) / d)
    p2 = (mx - h * (c2[1] - c1[1]) / d, my + h * (c2[0] - c1[0]) / d)
    return p1, p2

def ang(c, p):
    return math.atan2(p[1] - c[1], p[0] - c[0])

def cloud(dx=0, dy=0, s=1.0):
    """Cloud: left/top/right circles over a flat bottom (base box 9.5..40 x 12.5..34)."""
    def T(x, y): return (24 + (x - 24) * s + dx, 24 + (y - 24) * s + dy)
    L, rl = T(15, 28.5), 5.5 * s
    M, rm = T(24, 21), 8.5 * s
    R, rr = T(34, 28), 6 * s
    lm = min(circ_intersect(L, rl, M, rm), key=lambda p: p[1])
    mr = min(circ_intersect(M, rm, R, rr), key=lambda p: p[1])
    def up(a0, a1):
        while a1 <= a0: a1 += 2 * math.pi
        return a1
    a0 = math.pi / 2; a1 = up(a0, ang(L, lm))
    pts = arc(*L, rl, a0, a1)
    b0 = ang(M, lm); b1 = up(b0, ang(M, mr))
    pts += arc(*M, rm, b0, b1)[1:]
    c0 = ang(R, mr); c1 = up(c0, math.pi / 2)
    pts += arc(*R, rr, c0, c1)[1:]
    pts += seg(pts[-1], pts[0])[1:-1]
    return pts

def inside(pt, poly):
    x, y = pt; c = False; n = len(poly)
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: c = not c
    return c

def crescent(c1, r1, c2, r2):
    """Disc c1 minus disc c2."""
    p, q = circ_intersect(c1, r1, c2, r2)
    a, b = ang(c1, p), ang(c1, q)
    def sweep(c, r, s, e, want_inside, other_c, other_r):
        for direction in (1, -1):
            ee = e
            if direction == 1:
                while ee <= s: ee += 2 * math.pi
            else:
                while ee >= s: ee -= 2 * math.pi
            mid = (s + ee) / 2
            mp = (c[0] + r * math.cos(mid), c[1] + r * math.sin(mid))
            if (math.dist(mp, other_c) < other_r) == want_inside:
                return arc(*c, r, s, ee)
    outer = sweep(c1, r1, a, b, False, c2, r2)
    inner = sweep(c2, r2, ang(c2, q), ang(c2, p), True, c1, r1)
    return outer + inner[1:-1]

def drop(cx, cy, s=1.0):
    """Tear drop; (cx, cy) is the centre of the round bottom."""
    r = 2.3 * s; tip = (cx, cy - 5.2 * s)
    d = math.dist(tip, (cx, cy)); t = math.acos(r / d)
    base = -math.pi / 2
    pts = arc(cx, cy, r, base + t, base - t + 2 * math.pi)
    return seg(tip, pts[0])[:-1] + pts + seg(pts[-1], tip)[1:-1]

def flake(cx, cy, R=5.6):
    pts = []
    n = 240
    for i in range(n):
        t = 2 * math.pi * i / n
        r = R * (0.5 + 0.5 * ((1 + math.cos(6 * t)) / 2) ** 0.9)
        pts.append((cx + r * math.cos(t - math.pi / 2), cy + r * math.sin(t - math.pi / 2)))
    return pts

def sparkle(cx, cy, s):
    n = 120
    return [(cx + s * math.copysign(abs(math.cos(t)) ** 3, math.cos(t)),
             cy + s * math.copysign(abs(math.sin(t)) ** 3, math.sin(t)))
            for t in (2 * math.pi * i / n for i in range(n))]

def poly(points):
    out = []
    for i in range(len(points)):
        out += seg(points[i], points[(i + 1) % len(points)])[:-1]
    return out

def sun(cx, cy, r, r0, r1, w, rays=8, start=0.0):
    layers = []
    for i in range(rays):
        a = start + 2 * math.pi * i / rays
        layers.append(Layer(pill((cx + r0 * math.cos(a), cy + r0 * math.sin(a)),
                                 (cx + r1 * math.cos(a), cy + r1 * math.sin(a)), w), YELLOW))
    layers.append(Layer(circle(cx, cy, r), YELLOW))
    return layers

def curl(x0, y, x1, cy_off, r0, r1, sweep_deg, down):
    """Straight line ending in a spiral curl (wind)."""
    pts = seg((x0, y), (x1, y))
    if down:   # curl below the line: start at top of circle, clockwise
        c = (x1, y + r0); a0 = -math.pi / 2; sign = 1
    else:      # curl above the line: start at bottom, counter-clockwise
        c = (x1, y - r0); a0 = math.pi / 2; sign = -1
    n = 120; sw = math.radians(sweep_deg)
    for i in range(1, n + 1):
        f = i / n; a = a0 + sign * sw * f; r = r0 + (r1 - r0) * f
        pts.append((c[0] + r * math.cos(a), c[1] + r * math.sin(a)))
    return pts


# ---------- icon definitions (48x48 viewport) ----------
def precip_cloud(dark=False):
    return Layer(cloud(0, -8), DARK if dark else CLOUD)

def rain_streaks(xs, top, lens, w=1.7):
    return [Layer(pill((x, top), (x - 0.32 * l, top + l), w), BLUE) for x, l in zip(xs, lens)]

ICONS = {}
ICONS['clear'] = sun(24, 24, 7.5, 11.5, 17.5, 2.0)
ICONS['clear_night'] = [Layer(crescent((21, 26), 13, (27.5, 20), 10.5), MOON),
                        Layer(sparkle(37.5, 10.5, 4.6), BLACK), Layer(sparkle(42, 19.5, 3.2), BLACK)]
ICONS['partly_cloudy'] = sun(18, 17, 6.5, 10, 14.5, 1.8) + [Layer(cloud(4, 6), CLOUD)]
ICONS['partly_cloudy_night'] = [Layer(crescent((21, 15), 9.5, (26.5, 10.5), 8), MOON),
                                Layer(cloud(1, 7), CLOUD)]
ICONS['cloudy'] = [Layer(cloud(0, 1, 1.12), CLOUD)]
ICONS['overcast'] = [Layer(cloud(6, -5, 0.85), DARK), Layer(cloud(-3, 4), CLOUD)]
ICONS['fog'] = [Layer(cloud(0, -6), CLOUD),
                Layer(pill((8, 35), (23, 35), 2.2), GREY), Layer(pill((15, 41.5), (39, 41.5), 2.2), GREY)]
ICONS['drizzle'] = [precip_cloud(), Layer(drop(16.5, 35), BLUE), Layer(drop(31.5, 35), BLUE),
                    Layer(drop(24, 41.5), BLUE)]
ICONS['rain'] = [precip_cloud()] + rain_streaks([20, 27, 34], 31, [9, 13, 9])
ICONS['heavy_rain'] = [precip_cloud(True)] + rain_streaks([16.5, 23, 29.5, 36], 31, [10, 13, 13, 9])
ICONS['thunderstorm'] = [precip_cloud(True),
                         Layer(poly([(27, 25), (21, 35.5), (25.5, 35.5), (21.5, 45.5),
                                     (31, 31.5), (26.5, 31.5), (30, 25)]), YELLOW),
                         Layer(drop(14.5, 35.5), BLUE), Layer(drop(36, 35.5), BLUE)]
ICONS['snow'] = [precip_cloud(), Layer(flake(17.5, 36, 5.8), ICE), Layer(flake(31, 37.5, 5.8), ICE)]
ICONS['sleet'] = [precip_cloud(), Layer(drop(16, 37), BLUE), Layer(flake(30, 37.5, 5.8), ICE)]
ICONS['hail'] = [precip_cloud(True), Layer(circle(16, 33, 2.6), ICE), Layer(circle(32, 33, 2.6), ICE),
                 Layer(circle(24, 40.5, 2.6), ICE)]
ICONS['windy'] = [Layer(thick(curl(6, 17, 29, 0, 5, 3.4, 250, False), 1.8), GREY),
                  Layer(thick(curl(6, 24, 37, 0, 5, 3.4, 250, False), 1.8), GREY),
                  Layer(thick(curl(10, 31.5, 29, 0, 5, 3.4, 250, True), 1.8), GREY)]
ICONS['tornado'] = [Layer(pill(p, q, 2.4), DARK) for p, q in
                    [((7, 9), (41, 9)), ((11, 15.5), (36, 15.5)), ((14.5, 22), (32, 22)),
                     ((17.5, 28.5), (29, 28.5)), ((20, 35), (26.5, 35))]]
ICONS['hot'] = [Layer(pill((21, 8), (21, 30), 4.2), WHITE), Layer(circle(21, 36.5, 7), WHITE),
                Layer(pill((21, 15), (21, 34), 1.6), RED), Layer(circle(21, 36.5, 3.8), RED),
                Layer(pill((30, 10), (34.5, 10), 1.1), BLACK), Layer(pill((30, 15), (34.5, 15), 1.1), BLACK),
                Layer(pill((30, 20), (34.5, 20), 1.1), BLACK)]
_half = arc(24, 33, 8.5, math.pi, 2 * math.pi)
ICONS['sunrise'] = [Layer(pill((24, 15.5), (24, 20), 1.9), YELLOW),
                    Layer(pill((13.2, 22.2), (16.4, 25.4), 1.9), YELLOW),
                    Layer(pill((34.8, 22.2), (31.6, 25.4), 1.9), YELLOW),
                    Layer(_half + seg(_half[-1], _half[0])[1:-1], YELLOW),
                    Layer(seg((5, 33), (43, 33)), None, closed=False),
                    Layer(pill((16.5, 40), (31.5, 40), 2.2), ORANGE)]

# black detail shapes (stars, tick marks) stay filled in the outline variant too
for name in ('clear_night', 'hot'):
    for l in ICONS[name]:
        if l.fill == BLACK: l.keep_fill_in_outline = True


# ---------- output ----------
def fmt(v): return ('%.2f' % v).rstrip('0').rstrip('.')

def path_d(pts, closed):
    s = 'M' + fmt(pts[0][0]) + ',' + fmt(pts[0][1])
    s += ''.join('L' + fmt(x) + ',' + fmt(y) for x, y in pts[1:])
    return s + ('Z' if closed else '')

def outline_runs(layers, i):
    """Visible parts of layer i's outline, given the solid layers in front of it."""
    l = layers[i]
    covers = [m.pts for m in layers[i + 1:] if m.solid]
    pts = l.pts
    if l.closed:
        dense = []
        for k in range(len(pts)):
            dense += seg(pts[k], pts[(k + 1) % len(pts)])[:-1]
    else:
        dense = []
        for k in range(len(pts) - 1):
            dense += seg(pts[k], pts[k + 1])[:-1]
        dense.append(pts[-1])
    vis = [not any(inside(p, c) for c in covers) for p in dense]
    if all(vis):
        return [(dense, l.closed)]
    if l.closed:  # rotate so we start on a hidden point
        k = vis.index(False); dense = dense[k:] + dense[:k]; vis = vis[k:] + vis[:k]
    runs, cur = [], []
    for p, v in zip(dense, vis):
        if v: cur.append(p)
        elif cur:
            if len(cur) > 1: runs.append((cur, False))
            cur = []
    if len(cur) > 1: runs.append((cur, False))
    return runs

def simplify(pts, tol=0.06):
    if len(pts) < 3: return pts
    out = [pts[0]]
    for k in range(1, len(pts) - 1):
        a, b, c = out[-1], pts[k], pts[k + 1]
        # drop b if it lies on the line a-c
        area = abs((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1]))
        if area / max(math.dist(a, c), 1e-9) > tol: out.append(b)
    out.append(pts[-1])
    return out

def build(name, outline):
    layers = ICONS[name]
    paths = []
    for i, l in enumerate(layers):
        if outline:
            if l.keep_fill_in_outline:
                paths.append((path_d(simplify(l.pts), True), l.fill, None))
                continue
            for run, closed in outline_runs(layers, i):
                paths.append((path_d(simplify(run), closed), None, STROKE))
        else:
            paths.append((path_d(simplify(l.pts), l.closed), l.fill if l.closed else None, STROKE))
    return paths

def vector_xml(paths):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Generated by tools/gen_icons.py - do not edit by hand. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="48dp" android:height="48dp"',
           '    android:viewportWidth="48" android:viewportHeight="48">']
    for d, fill, stroke in paths:
        attrs = ['android:pathData="%s"' % d]
        if fill: attrs.append('android:fillColor="%s"' % fill)
        if stroke:
            attrs += ['android:strokeColor="%s"' % stroke, 'android:strokeWidth="%s"' % SW,
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        out.append('    <path\n        ' + '\n        '.join(attrs) + ' />')
    out.append('</vector>')
    return '\n'.join(out) + '\n'

def svg_color(c): return '#' + c[3:] if c else 'none'

def svg_sheet(path):
    names = list(ICONS)
    cols = 6; cell = 110
    rows = (len(names) + cols - 1) // cols
    h = rows * cell * 2 + 40
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d"><rect width="100%%" height="100%%" fill="#87CAC1"/>' % (cols * cell, h)]
    for v, outline in enumerate((False, True)):
        for i, n in enumerate(names):
            x = (i % cols) * cell + 15; y = v * (rows * cell + 40) + (i // cols) * cell + 5
            out.append('<g transform="translate(%d,%d) scale(1.65)">' % (x, y))
            for d, fill, stroke in build(n, outline):
                out.append('<path d="%s" fill="%s" stroke="%s" stroke-width="%s" stroke-linecap="round" stroke-linejoin="round"/>'
                           % (d, svg_color(fill), svg_color(stroke), SW))
            out.append('</g><text x="%d" y="%d" font-size="11" font-family="sans-serif">%s</text>' % (x, y + 92, n))
    out.append('</svg>')
    open(path, 'w').write('\n'.join(out))

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for n in ICONS:
        open(os.path.join(OUT, 'wx_%s.xml' % n), 'w').write(vector_xml(build(n, False)))
        open(os.path.join(OUT, 'wx_%s_outline.xml' % n), 'w').write(vector_xml(build(n, True)))
    if '--svg' in sys.argv:
        svg_sheet(sys.argv[sys.argv.index('--svg') + 1])
    print('generated %d icons' % len(ICONS))
