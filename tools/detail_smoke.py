"""Fumée violette et bleue du coin bas-droit des fiches (image originale générée, fond transparent)."""
import sys

import numpy as np
from PIL import Image, ImageFilter

W, H = 600, 960
rng = np.random.default_rng(5)


def noise(octaves, base, sx=1.0, sy=1.0):
    acc = np.zeros((H, W), np.float32)
    amp = tot = 0.0
    amp = 1.0
    for o in range(octaves):
        nx, ny = max(2, int(base * 2 ** o * sx)), max(2, int(base * 2 ** o * sy * H / W))
        small = (rng.random((ny, nx)) * 255).astype(np.uint8)
        acc += np.asarray(Image.fromarray(small).resize((W, H), Image.BICUBIC), np.float32) / 255 * amp
        tot += amp
        amp *= 0.55
    acc /= tot
    return (acc - acc.min()) / (acc.max() - acc.min())


yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xx / W, yy / H
# volutes : bruit étiré verticalement puis déformé (la fumée monte en ondulant)
warp = noise(4, 3) * 2 - 1
base = noise(6, 2, sx=1.6, sy=0.6)
xs = np.clip((xx + warp * 90).astype(int), 0, W - 1)
smoke = base[yy.astype(int), xs]
ridges = 1 - np.abs(smoke * 2 - 1)            # filaments
ridges = np.clip((ridges - 0.55) * 2.4, 0, 1) ** 1.4
body = np.clip((smoke - 0.45) * 1.8, 0, 1)
# présence : forte en bas à droite, s'éteint vers le haut et la gauche
corner = np.clip((u - 0.15) / 0.85, 0, 1) ** 1.3 * np.clip((v - 0.2) / 0.8, 0, 1) ** 1.1
a = np.clip((ridges * 1.15 + body * 0.4) * corner * 1.35, 0, 1)
# couleur : violet au cœur, magenta, bleu sur les bords
t = np.clip(noise(3, 2), 0, 1)[..., None]
violet, magenta, blue = np.array([139, 92, 246]), np.array([192, 132, 252]), np.array([96, 165, 250])
col = violet * (1 - t) + blue * t
col = col * (1 - ridges[..., None] * 0.35) + magenta * ridges[..., None] * 0.35
img = Image.fromarray(np.dstack([col, a * 235]).astype(np.uint8), 'RGBA')
glow = img.filter(ImageFilter.GaussianBlur(14))
out = Image.alpha_composite(glow, img).filter(ImageFilter.GaussianBlur(1.2))
out.save(sys.argv[1], optimize=True)
print('ok')
