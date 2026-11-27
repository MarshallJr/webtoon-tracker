"""Fonds originaux de la fiche « Découvrir » (générés, aucune image tierce) : château sous l'orage,
pics sous une éclipse, ruines flottantes près d'une faille. Ciel violet, éclairs, fumée, braises.
Usage : python3 tools/sheet_bg.py <dossier de sortie>"""
import math
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 900, 1900


class Scene:
    def __init__(self, seed):
        self.rnd = random.Random(seed)
        self.rng = np.random.default_rng(seed)
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
        self.u, self.v = xx / W, yy / H
        self.img = None

    def noise(self, octaves=6, base=3, sx=1.0, sy=1.0, persistence=0.55):
        acc = np.zeros((H, W), np.float32)
        amp, tot = 1.0, 0.0
        for o in range(octaves):
            nx = max(2, int(base * 2 ** o * sx))
            ny = max(2, int(base * 2 ** o * sy * H / W))
            small = (self.rng.random((ny, nx)) * 255).astype(np.uint8)
            acc += np.asarray(Image.fromarray(small).resize((W, H), Image.BICUBIC), np.float32) / 255 * amp
            tot += amp
            amp *= persistence
        acc /= tot
        return (acc - acc.min()) / (acc.max() - acc.min())

    def sky(self, lx, ly, power=1.0):
        u, v = self.u, self.v
        top, mid, bot = np.array([14, 8, 32]), np.array([24, 11, 50]), np.array([9, 7, 18])
        t = np.clip(v / 0.4, 0, 1)[..., None]
        s = top * (1 - t) + mid * t
        t2 = np.clip((v - 0.4) / 0.6, 0, 1)[..., None]
        s = s * (1 - t2) + bot * t2
        clouds = np.clip((self.noise(6, 3) * 0.7 + self.noise(5, 6) * 0.3 - 0.36) * 2.4, 0, 1)
        d = np.sqrt(((u - lx) * 1.0) ** 2 + ((v - ly) * 0.55) ** 2)
        light = np.exp(-d * 3.4) * power
        violet, magenta, deep = np.array([124, 58, 237]), np.array([190, 90, 240]), np.array([40, 18, 80])
        cl = clouds[..., None]
        s = s * (1 - cl * 0.8) + (deep * (1 - light[..., None]) + (violet * 0.7 + magenta * 0.3) * light[..., None]) * cl
        s += violet * (light ** 1.7)[..., None] * 0.7
        wisps = np.clip((self.noise(5, 4) - 0.6) * 3.0, 0, 1)
        s *= (1 - wisps[..., None] * 0.5)
        self.img = Image.fromarray(np.clip(s, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')

    def layer(self, draw_fn, blurs):
        base = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        draw_fn(ImageDraw.Draw(base))
        out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        for radius, gain in blurs:
            g = base.filter(ImageFilter.GaussianBlur(radius)) if radius else base
            if gain != 1:
                r, gg, b, a = g.split()
                g = Image.merge('RGBA', (r, gg, b, a.point(lambda x, k=gain: min(255, int(x * k)))))
            out = Image.alpha_composite(out, g)
        return out

    def screen(self, layer):
        a = np.asarray(self.img, np.float32)
        b = np.asarray(layer, np.float32)
        al = b[..., 3:4] / 255
        rgb = 255 - (255 - a[..., :3]) * (255 - b[..., :3] * al) / 255
        self.img = Image.fromarray(np.dstack([np.clip(rgb, 0, 255), a[..., 3]]).astype(np.uint8), 'RGBA')

    def over(self, layer):
        self.img = Image.alpha_composite(self.img, layer)

    def bolt(self, dr, x0, y0, x1, y1, width, depth=7, spread=0.3):
        rnd = self.rnd
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
        for _ in range(2):
            i = rnd.randint(len(pts) // 4, len(pts) - 2)
            ax, ay = pts[i]
            ang = math.atan2(y1 - y0, x1 - x0) + rnd.uniform(-0.9, 0.9)
            ln = math.hypot(x1 - x0, y1 - y0) * rnd.uniform(0.15, 0.3)
            if depth > 4:
                self.bolt(dr, ax, ay, ax + math.cos(ang) * ln, ay + math.sin(ang) * ln, max(1, width - 2), depth - 2, spread)

    def bolts(self, segments):
        self.screen(self.layer(lambda dr: [self.bolt(dr, *s) for s in segments], [(36, 2.4), (13, 2.2), (4, 1.5), (0, 1)]))

    def smoke(self, cx, strength=1.0):
        """Volutes violettes et bleues qui montent du bas, centrées sur cx (0..1)."""
        u, v = self.u, self.v
        warp = self.noise(4, 3) * 2 - 1
        base = self.noise(6, 2, sx=1.5, sy=0.5)
        yy = (v * (H - 1)).astype(int)
        xs = np.clip((u * W + warp * 80).astype(int), 0, W - 1)
        sm = base[yy, xs]
        ridges = np.clip(((1 - np.abs(sm * 2 - 1)) - 0.55) * 2.4, 0, 1) ** 1.4
        body = np.clip((sm - 0.45) * 1.8, 0, 1)
        where = np.exp(-((u - cx) / 0.32) ** 2) * np.clip((v - 0.62) / 0.38, 0, 1) ** 1.2
        a = np.clip((ridges * 1.1 + body * 0.35) * where * 1.3 * strength, 0, 1)
        t = self.noise(3, 2)[..., None]
        col = np.array([139, 92, 246]) * (1 - t) + np.array([96, 165, 250]) * t
        col = col * (1 - ridges[..., None] * 0.3) + np.array([200, 150, 255]) * ridges[..., None] * 0.3
        lay = Image.fromarray(np.dstack([col, a * 230]).astype(np.uint8), 'RGBA')
        self.screen(Image.alpha_composite(lay.filter(ImageFilter.GaussianBlur(12)), lay))

    def fog(self, y, thickness, strength=1.0, x0=0.0):
        """Brume violette en bande horizontale (estompe le pied des silhouettes)."""
        u, v = self.u, self.v
        a = np.exp(-((v - y) / thickness) ** 2) * (0.35 + 0.65 * self.noise(4, 4, sy=0.5)) * strength
        a *= np.clip((u - x0) / 0.25, 0, 1)
        col = np.array([70, 34, 130], np.float32)
        lay = Image.fromarray(np.dstack([np.broadcast_to(col, (H, W, 3)), np.clip(a, 0, 1) * 255]).astype(np.uint8), 'RGBA')
        self.over(lay)

    def embers(self, cx, cy, spread, n=220):
        rnd, rng = self.rnd, self.rng

        def draw(dr):
            for _ in range(n):
                r = abs(rng.normal(0, spread))
                ang = rnd.uniform(0, 2 * math.pi)
                x, y = cx * W + math.cos(ang) * r, cy * H + math.sin(ang) * r * 1.6
                s = rnd.choice([1, 1, 2, 2, 3])
                c = rnd.choice([(237, 233, 254), (196, 181, 253), (232, 121, 249)])
                dr.ellipse((x - s, y - s, x + s, y + s), fill=c + (rnd.randint(110, 240),))
        self.screen(self.layer(draw, [(5, 1.8), (0, 1)]))

    def finish(self, path, left_dark=0.55):
        a = np.asarray(self.img, np.float32)[..., :3]
        u, v = self.u, self.v
        # lisibilité : colonne de texte (gauche et centre) assombrie, haut un peu voilé
        dark = np.clip(1 - u / 0.75, 0, 1) ** 1.3 * left_dark + 0.18
        a *= (1 - dark[..., None])
        top = np.clip(1 - v / 0.1, 0, 1) * 0.35
        a = a * (1 - top[..., None]) + np.array([11, 9, 20]) * top[..., None]
        Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), 'RGB').save(path, quality=82, optimize=True,
                                                                          progressive=True)


def castle(out):
    s = Scene(41)
    s.sky(0.82, 0.28, 1.1)
    # château sur sa falaise, à droite
    def draw(dr):
        col = (10, 6, 20, 255)
        base = 0.44 * H
        dr.polygon([(0.58 * W, base + 20), (0.7 * W, base + 10), (0.84 * W, base + 30), (W + 10, base - 10),
                    (W + 10, base + 260), (0.93 * W, base + 200), (0.86 * W, base + 300), (0.8 * W, base + 170),
                    (0.72 * W, base + 210), (0.66 * W, base + 90)], fill=col)          # éperon rocheux
        dr.rectangle((0.6 * W, base - 70, 0.98 * W, base + 30), fill=col)            # remparts
        for x in range(int(0.6 * W), int(0.98 * W), 22):                           # créneaux
            dr.rectangle((x, base - 84, x + 11, base - 70), fill=col)
        towers = [(0.63, 150, 34), (0.71, 250, 40), (0.79, 330, 46), (0.87, 230, 38), (0.94, 170, 32)]
        for cx, h, w in towers:
            x = cx * W
            dr.rectangle((x - w / 2, base - h, x + w / 2, base), fill=col)
            dr.polygon([(x - w / 2 - 6, base - h), (x, base - h - w * 2.2), (x + w / 2 + 6, base - h)], fill=col)
            for wy in range(int(base - h + 30), int(base - 20), 46):              # fenêtres allumées
                dr.rectangle((x - 3, wy, x + 3, wy + 12), fill=(196, 160, 255, 255))
        dr.line((0.79 * W, base - 330 - 46 * 2.2, 0.79 * W, base - 330 - 46 * 2.2 - 40), fill=col, width=3)
    s.screen(s.layer(lambda dr: dr.ellipse((0.62 * W, 0.12 * H, 1.1 * W, 0.42 * H), fill=(139, 92, 246, 120)),
                     [(90, 1.0)]))
    s.over(s.layer(draw, [(0, 1)]))
    s.screen(s.layer(lambda dr: [dr.rectangle((0.6 * W, 0.44 * H - 72, 0.98 * W, 0.44 * H - 70),
                                              fill=(167, 139, 250, 140))], [(3, 1.5), (0, 1)]))
    s.fog(0.53, 0.06, 0.9, 0.45)
    s.fog(0.6, 0.05, 0.7, 0.5)
    s.bolts([(0.92 * W, -10, 0.8 * W, 0.44 * H - 400, 5), (0.7 * W, 0.02 * H, 0.98 * W, 0.2 * H, 3)])
    s.smoke(0.1, 1.0)
    s.smoke(0.92, 1.1)
    s.embers(0.82, 0.3, 160)
    s.finish(out)


def peaks(out):
    s = Scene(73)
    s.sky(0.78, 0.12, 1.0)
    # éclipse : disque sombre et couronne violette
    cx, cy, r = 0.76 * W, 0.12 * H, 120
    s.screen(s.layer(lambda dr: dr.ellipse((cx - r - 40, cy - r - 40, cx + r + 40, cy + r + 40),
                                           fill=(196, 160, 255, 255)), [(60, 1.4), (22, 1.3)]))
    s.over(s.layer(lambda dr: dr.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(8, 5, 16, 255)), [(0, 1)]))
    s.screen(s.layer(lambda dr: dr.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(237, 233, 254, 255),
                                           width=3), [(8, 2), (0, 1)]))
    # chaînes de pics, deux plans
    for depth, (base, amp, col) in enumerate([(0.5, 0.16, (24, 12, 46, 255)), (0.58, 0.2, (9, 6, 18, 255))]):
        def draw(dr, base=base, amp=amp, col=col, depth=depth):
            pts, x = [(-10, H)], -10
            while x < W + 40:
                pts.append((x, (base - s.rnd.uniform(0.2, 1.0) * amp) * H))
                x += s.rnd.randint(50, 120)
                pts.append((x, (base - s.rnd.uniform(0.0, 0.35) * amp) * H))
                x += s.rnd.randint(40, 90)
            pts.append((W + 40, H))
            dr.polygon(pts, fill=col)
        s.over(s.layer(draw, [(1.5 if depth == 0 else 0, 1)]))
        s.fog(base - 0.02, 0.04, 0.55 if depth == 0 else 0.8)
    s.bolts([(0.86 * W, 0.18 * H, 0.95 * W, 0.42 * H, 4), (0.66 * W, 0.17 * H, 0.52 * W, 0.36 * H, 3)])
    s.smoke(0.15, 0.9)
    s.smoke(0.88, 1.1)
    s.embers(0.76, 0.2, 200)
    s.finish(out)


def ruins(out):
    s = Scene(97)
    s.sky(0.8, 0.22, 1.1)
    # faille verticale lumineuse derrière l'île
    def rift(dr, widen):
        cx, cy, hgt = 0.8 * W, 0.22 * H, 0.13 * H
        L, R = [], []
        for k in range(27):
            f = k / 26
            y = cy - hgt + 2 * hgt * f
            wv = math.sin(math.pi * f) ** 0.9 * 22 * widen
            j = (7 if k % 2 else -7) * math.sin(math.pi * f)
            L.append((cx - wv + j, y))
            R.append((cx + wv + j, y))
        dr.polygon(L + R[::-1], fill=(255, 255, 255, 255))
    s.screen(s.layer(lambda dr: rift(dr, 3.0), [(110, 1.3), (45, 1.1)]))
    s.screen(s.layer(lambda dr: rift(dr, 1.0), [(12, 1.2), (3, 1), (0, 1)]))
    # île flottante et colonnes brisées
    def island(dr):
        col = (10, 6, 20, 255)
        top = 0.4 * H
        dr.polygon([(0.55 * W, top), (W + 10, top - 20), (W + 10, top + 30), (0.9 * W, top + 140),
                    (0.8 * W, top + 230), (0.72 * W, top + 150), (0.6 * W, top + 60)], fill=col)
        for k, (x, h) in enumerate([(0.62, 120), (0.7, 200), (0.78, 90), (0.86, 160), (0.93, 110)]):
            xx = x * W
            dr.rectangle((xx - 12, top - h, xx + 12, top + 4), fill=col)
            dr.polygon([(xx - 14, top - h), (xx - 4, top - h - 18), (xx + 6, top - h - 6), (xx + 14, top - h)], fill=col)
        dr.rectangle((0.6 * W, 0.4 * H - 210, 0.75 * W, 0.4 * H - 196), fill=col)    # linteau
    s.over(s.layer(island, [(0, 1)]))
    s.screen(s.layer(lambda dr: dr.line([(0.56 * W, 0.4 * H), (W, 0.4 * H - 20)], fill=(167, 139, 250, 160), width=2),
                     [(4, 1.6), (0, 1)]))
    s.fog(0.5, 0.05, 0.6, 0.4)
    s.bolts([(0.8 * W, 0.1 * H, 0.98 * W, 0.0, 4), (0.82 * W, 0.33 * H, 0.95 * W, 0.5 * H, 3)])
    s.smoke(0.08, 1.0)
    s.smoke(0.9, 1.0)
    s.embers(0.8, 0.24, 170)
    s.finish(out)


if __name__ == '__main__':
    d = sys.argv[1]
    castle(f'{d}/sheet_bg_castle.jpg')
    peaks(f'{d}/sheet_bg_peaks.jpg')
    ruins(f'{d}/sheet_bg_ruins.jpg')
    print('ok')
