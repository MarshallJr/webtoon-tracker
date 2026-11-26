"""Illustration originale du fond de l'accueil : une faille violette s'ouvre dans un ciel d'orage
au-dessus d'une ville, éclairs et particules. Entièrement générée (aucune image tierce)."""
import math
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1200, 1180
rnd = random.Random(7)
np_rng = np.random.default_rng(7)


def noise(octaves=6, base=4, persistence=0.55):
    acc = np.zeros((H, W), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        s = base * 2 ** o
        small = np_rng.random((max(2, int(s * H / W)), s)).astype(np.float32)
        layer = np.asarray(Image.fromarray((small * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC),
                           np.float32) / 255.0
        acc += layer * amp
        tot += amp
        amp *= persistence
    acc /= tot
    return (acc - acc.min()) / (acc.max() - acc.min())


yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xx / W, yy / H
CX, CY = 0.70, 0.36          # centre de la faille

# 1) Ciel : dégradé nuit violette
sky = np.zeros((H, W, 3), np.float32)
top, mid, bot = np.array([18, 8, 38]), np.array([30, 12, 58]), np.array([7, 6, 11])
t = np.clip(v / 0.55, 0, 1)[..., None]
sky = top * (1 - t) + mid * t
t2 = np.clip((v - 0.55) / 0.45, 0, 1)[..., None]
sky = sky * (1 - t2) + bot * t2

# 2) Nuages d'orage (bruit fractal) éclairés par la faille
n1, n2 = noise(6, 3), noise(5, 6)
clouds = np.clip((n1 * 0.7 + n2 * 0.3 - 0.36) * 2.6, 0, 1)
d = np.sqrt(((u - CX) * 1.1) ** 2 + ((v - CY) * 0.9) ** 2)
light = np.exp(-d * 3.2)
upper = np.clip(1.15 - v / 0.62, 0, 1)
violet, magenta, deep = np.array([124, 58, 237]), np.array([192, 80, 230]), np.array([45, 20, 90])
cl = (clouds * upper)[..., None]
sky = sky * (1 - cl * 0.85) + (deep * (1 - light[..., None]) + (violet * 0.75 + magenta * 0.25) * light[..., None]) * cl * 1.05
sky += (violet * 0.55)[None, None, :] * (light ** 1.6)[..., None] * 0.9
# volutes sombres devant la lumière (profondeur)
wisps = np.clip((noise(5, 4) - 0.58) * 3.0, 0, 1) * upper
sky *= (1 - wisps[..., None] * 0.55)

img = Image.fromarray(np.clip(sky, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')


def glow_layer(draw_fn, blurs):
    """Dessine une forme puis l'empile avec ses lueurs floutées (du plus large au plus net)."""
    base = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(base))
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for radius, gain in blurs:
        g = base.filter(ImageFilter.GaussianBlur(radius)) if radius else base
        if gain != 1:
            r, gg, b, a = g.split()
            a = a.point(lambda x: min(255, int(x * gain)))
            g = Image.merge('RGBA', (r, gg, b, a))
        out = Image.alpha_composite(out, g)
    return out


def add(layer):
    global img
    # mélange « écran » : la lumière s'additionne
    a = np.asarray(img, np.float32)
    b = np.asarray(layer, np.float32)
    alpha = b[..., 3:4] / 255.0
    rgb = 255 - (255 - a[..., :3]) * (255 - b[..., :3] * alpha) / 255
    img = Image.fromarray(np.dstack([np.clip(rgb, 0, 255), a[..., 3]]).astype(np.uint8), 'RGBA')


# 3) Rayons de lumière depuis la faille
def rays(dr):
    cx, cy = CX * W, CY * H
    for k in range(26):
        ang = rnd.uniform(0, 2 * math.pi)
        ln = rnd.uniform(250, 700)
        wd = rnd.uniform(0.01, 0.035)
        p = [(cx, cy), (cx + math.cos(ang - wd) * ln, cy + math.sin(ang - wd) * ln),
             (cx + math.cos(ang + wd) * ln, cy + math.sin(ang + wd) * ln)]
        dr.polygon(p, fill=(167, 139, 250, rnd.randint(14, 30)))


add(glow_layer(rays, [(18, 1)]))


# 4) Tourbillon d'énergie autour de la faille
def swirl(dr):
    cx, cy = CX * W, CY * H
    for k in range(6):
        rx, ry = rnd.uniform(120, 250), rnd.uniform(36, 80)
        rot = rnd.uniform(-0.5, 0.2)
        a0 = rnd.uniform(0, 2 * math.pi)
        span = rnd.uniform(1.6, 3.6)
        pts = []
        for s in range(90):
            a = a0 + span * s / 89
            x, y = rx * math.cos(a), ry * math.sin(a)
            pts.append((cx + x * math.cos(rot) - y * math.sin(rot), cy + x * math.sin(rot) + y * math.cos(rot)))
        col = rnd.choice([(196, 181, 253), (167, 139, 250), (232, 121, 249)])
        dr.line(pts, fill=col + (220,), width=rnd.choice([2, 3, 4]), joint='curve')


add(glow_layer(swirl, [(30, 2.2), (10, 1.8), (2, 1.2), (0, 0.8)]))


# 5) La faille : déchirure verticale irrégulière, cœur blanc
def rift(dr, widen=1.0):
    cx, cy = CX * W, CY * H
    hgt = 0.24 * H
    left, right = [], []
    steps = 26
    for s in range(steps + 1):
        f = s / steps
        y = cy - hgt + 2 * hgt * f
        wv = math.sin(math.pi * f) ** 0.9 * 32 * widen
        j = (8 if s % 2 else -8) * math.sin(math.pi * f) + rnd.uniform(-3, 3)
        off = math.sin(f * 5.0) * 14
        left.append((cx - wv + j + off, y))
        right.append((cx + wv + j + off, y))
    dr.polygon(left + right[::-1], fill=(255, 255, 255, 255))


add(glow_layer(lambda dr: rift(dr, 3.0), [(140, 1.5), (60, 1.3), (25, 1.0)]))
rnd.seed(11)
core = glow_layer(lambda dr: rift(dr, 1.0), [(14, 1.2), (4, 1), (0, 1)])
# teinte : violet sur les bords, blanc au centre
c = np.asarray(core, np.float32)
c[..., 0] = np.minimum(255, c[..., 0] * 0.95 + 10)
c[..., 1] = c[..., 1] * 0.72
add(Image.fromarray(c.astype(np.uint8), 'RGBA'))


# 6) Éclairs (déplacement du point milieu), avec lueur
def bolt(dr, x0, y0, x1, y1, width, depth=7, spread=0.32):
    pts = [(x0, y0), (x1, y1)]
    for _ in range(depth):
        nxt = [pts[0]]
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            mx, my = (ax + bx) / 2, (ay + by) / 2
            ln = math.hypot(bx - ax, by - ay)
            nx, ny = -(by - ay) / (ln + 1e-6), (bx - ax) / (ln + 1e-6)
            o = rnd.uniform(-spread, spread) * ln
            nxt += [(mx + nx * o, my + ny * o), (bx, by)]
        pts = nxt
    dr.line(pts, fill=(221, 214, 254, 255), width=width, joint='curve')
    # ramifications
    for _ in range(3):
        i = rnd.randint(len(pts) // 4, len(pts) - 2)
        ax, ay = pts[i]
        ang = math.atan2(y1 - y0, x1 - x0) + rnd.uniform(-0.9, 0.9)
        ln = math.hypot(x1 - x0, y1 - y0) * rnd.uniform(0.15, 0.3)
        if depth > 4:
            bolt(dr, ax, ay, ax + math.cos(ang) * ln, ay + math.sin(ang) * ln, max(1, width - 2), depth - 2, spread)


def bolts(dr):
    cx, cy = CX * W, CY * H
    bolt(dr, cx + 10, cy - 0.25 * H, W * 0.98, -20, 5)
    bolt(dr, cx + 20, cy + 0.2 * H, W * 1.02, H * 0.55, 4)


rnd.seed(3)
add(glow_layer(bolts, [(42, 2.6), (16, 2.4), (5, 1.6), (0, 1)]))

# 7) Ville : deux rangées d'immeubles, fenêtres allumées, toits éclairés par la faille
city = Image.new('RGBA', (W, H), (0, 0, 0, 0))
cd = ImageDraw.Draw(city)
rnd.seed(21)
for layer, (base_y, hmin, hmax, col, win_a) in enumerate(
        [(0.84, 0.05, 0.13, (24, 13, 48, 255), 60), (0.90, 0.06, 0.17, (8, 5, 16, 255), 140)]):
    x = -20
    while x < W + 20:
        bw = rnd.randint(40, 110)
        bh = rnd.uniform(hmin, hmax) * H
        # plus haut près de la faille
        bh *= 1.0 - 0.35 * math.exp(-((x / W - CX) ** 2) / 0.01)
        y0 = base_y * H - bh
        cd.rectangle((x, y0, x + bw, H), fill=col)
        if rnd.random() < 0.25:
            ax = x + bw * rnd.uniform(0.3, 0.7)
            cd.line((ax, y0, ax, y0 - rnd.uniform(20, 60)), fill=col, width=3)
        # fenêtres
        for wy in range(int(y0 + 10), int(base_y * H + 0.12 * H), 16):
            for wx in range(x + 7, x + bw - 7, 13):
                if rnd.random() < 0.18:
                    c = rnd.choice([(196, 181, 253), (167, 139, 250), (125, 211, 252), (253, 230, 138)])
                    cd.rectangle((wx, wy, wx + 5, wy + 7), fill=c + (win_a,))
        # liseré lumineux en haut (reflet de la faille)
        lit = int(120 * math.exp(-((x / W - CX) ** 2) / 0.06))
        cd.line((x, y0, x + bw, y0), fill=(196, 181, 253, lit), width=2)
        x += bw + rnd.randint(0, 10)
    if layer == 0:
        city = city.filter(ImageFilter.GaussianBlur(1.2))
        cd = ImageDraw.Draw(city)
img = Image.alpha_composite(img, city)

# 8) Brume violette au pied des immeubles
a = np.asarray(img, np.float32)
fog = np.exp(-((v - 0.84) / 0.07) ** 2) * (0.35 + 0.65 * noise(4, 5))
a[..., :3] = a[..., :3] * (1 - fog[..., None] * 0.55) + np.array([88, 40, 160]) * fog[..., None] * 0.55
img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), 'RGBA')


# 9) Particules et étincelles
def sparks(dr):
    cx, cy = CX * W, CY * H
    for _ in range(320):
        r = abs(np_rng.normal(0, 260))
        ang = rnd.uniform(0, 2 * math.pi)
        x, y = cx + math.cos(ang) * r * 1.3, cy + math.sin(ang) * r * 0.9
        s = rnd.choice([1, 1, 2, 2, 3])
        c = rnd.choice([(237, 233, 254), (196, 181, 253), (232, 121, 249)])
        dr.ellipse((x - s, y - s, x + s, y + s), fill=c + (rnd.randint(120, 255),))
    for _ in range(9):
        x, y = rnd.uniform(0.45, 0.98) * W, rnd.uniform(0.05, 0.7) * H
        s = rnd.uniform(9, 18)
        dr.polygon([(x, y - s), (x + s * 0.22, y - s * 0.22), (x + s, y), (x + s * 0.22, y + s * 0.22),
                    (x, y + s), (x - s * 0.22, y + s * 0.22), (x - s, y), (x - s * 0.22, y - s * 0.22)],
                   fill=(237, 233, 254, 230))


add(glow_layer(sparks, [(6, 1.8), (0, 1)]))

# 10) Lisibilité du titre (gauche assombrie) et fondu vers le fond de l'appli en bas
a = np.asarray(img, np.float32)[..., :3]
left = np.clip(1 - u / 0.62, 0, 1) ** 1.4 * 0.72
a = a * (1 - left[..., None])
bg = np.array([7, 6, 11], np.float32)
fade = np.clip((v - 0.66) / 0.33, 0, 1) ** 1.2
a = a * (1 - fade[..., None]) + bg * fade[..., None]
topfade = np.clip(1 - v / 0.16, 0, 1) ** 1.2 * 0.8
a = a * (1 - topfade[..., None]) + bg * topfade[..., None]
Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), 'RGB').save(sys.argv[1], quality=86, optimize=True,
                                                                  progressive=True)
print('ok')
