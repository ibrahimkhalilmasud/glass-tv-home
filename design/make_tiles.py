"""Draw the glass app tiles (launcher banners), the TV Hub banner and the TV Hub icon.

Run from the repo root: python design/make_tiles.py   (OWNER_NAME=Alex python design/make_tiles.py)
Output: design/out/banner_<name>.png (640x360) and design/out/ic_hub.png (512x512).
Wordmarks are set in Outfit (OFL, bundled with the launcher); brand colours are used as accents only.
"""
import math
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT.parent / "reference" / "cleanyourtv" / "launcher" / "app" / "src" / "main" / "res" / "font" / "outfit.ttf"
OUT = ROOT / "design" / "out"
W, H = 640, 360
SS = 2  # supersampling factor
RADIUS = 44
OWNER = os.environ.get("OWNER_NAME", "Mike")  # shown on the TV Hub tile and icon: "<OWNER>'s TV"
TEXT_SCALE = 1.25  # labels are read from the couch, on 165 px tiles


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size * SS)
    try:
        f.set_variation_by_axes([weight])
    except (OSError, AttributeError):
        pass  # fixed-weight fallback
    return f


def rgb(hex_):
    hex_ = hex_.lstrip("#")
    return np.array([int(hex_[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def gradient(c1, c2, angle=65.0):
    w, h = W * SS, H * SS
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    a = math.radians(angle)
    t = (xs / w * math.cos(a) + ys / h * math.sin(a)) / (math.cos(a) + math.sin(a))
    t = np.clip(t, 0, 1)[..., None]
    return rgb(c1) * (1 - t) + rgb(c2) * t


def glow(arr, color, cx, cy, radius, strength):
    h, w = arr.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    d2 = ((xs - cx * SS) ** 2 + (ys - cy * SS) ** 2) / (radius * SS) ** 2
    arr += rgb(color) * (np.exp(-d2) * strength)[..., None]
    return arr


def sheen(img):
    """A curved glass highlight across the top and a thin bright edge."""
    w, h = img.size
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse((-0.35 * w, -1.25 * h, 1.35 * w, 0.52 * h), fill=(255, 255, 255, 30))
    layer = layer.filter(ImageFilter.GaussianBlur(6 * SS))
    img = Image.alpha_composite(img, layer)
    edge = Image.new("RGBA", img.size, (0, 0, 0, 0))
    e = ImageDraw.Draw(edge)
    e.rounded_rectangle((2 * SS, 2 * SS, w - 2 * SS, h - 2 * SS), RADIUS * SS, outline=(255, 255, 255, 70), width=3 * SS)
    e.rounded_rectangle((5 * SS, 5 * SS, w - 5 * SS, h - 5 * SS), (RADIUS - 3) * SS, outline=(255, 255, 255, 22), width=2 * SS)
    return Image.alpha_composite(img, edge)


def base(c1, c2, glow_color, gx=0.28, gy=0.2, strength=0.55):
    arr = gradient(c1, c2)
    arr = glow(arr, glow_color, W * gx, H * gy, 230, strength)
    arr = glow(arr, glow_color, W * 0.95, H * 1.0, 170, strength * 0.45)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    return sheen(img)


def text(img, s, cx, cy, size, weight, fill=(255, 255, 255, 255), spacing=0, shadow=True):
    f = font(int(size * TEXT_SCALE), weight)
    widths = [f.getlength(ch) for ch in s]
    total = sum(widths) + spacing * SS * (len(s) - 1)
    x = cx * SS - total / 2
    passes = [((0, 0, 0, 150), 10 * SS, 5 * SS)] if shadow else []
    passes.append((fill, 0, 0))
    for layer_fill, blur, off in passes:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        px = x
        for ch, cw in zip(s, widths):
            d.text((px, cy * SS + off), ch, font=f, fill=layer_fill, anchor="lm")
            px += cw + spacing * SS
        if blur:
            layer = layer.filter(ImageFilter.GaussianBlur(blur))
        img = Image.alpha_composite(img, layer)
    return img


def draw_on(img, fn):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    fn(ImageDraw.Draw(layer))
    return Image.alpha_composite(img, layer)


def S(*vals):
    return [v * SS for v in vals]


def tv_glyph(img, cx, cy, size, color):
    def go(d):
        w, h = size * 1.35, size
        d.rounded_rectangle(S(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), 14 * SS, outline=color, width=int(size * 0.09) * SS)
        d.line(S(cx - w * 0.18, cy - h / 2 - 2, cx - w * 0.34, cy - h / 2 - h * 0.28), fill=color, width=int(size * 0.07) * SS)
        d.line(S(cx + w * 0.18, cy - h / 2 - 2, cx + w * 0.34, cy - h / 2 - h * 0.28), fill=color, width=int(size * 0.07) * SS)
    return draw_on(img, go)


def tile_youtube():
    img = base("#1a0508", "#5c0f17", "#ff2a3a")

    def go(d):
        d.rounded_rectangle(S(230, 62, 410, 158), 30 * SS, fill=(255, 32, 48, 255))
        d.polygon(S(296, 84, 296, 136, 346, 110), fill=(255, 255, 255, 255))
    img = draw_on(img, go)
    return text(img, "YouTube", 320, 262, 56, 500)


def tile_netflix():
    img = base("#080304", "#2c070b", "#e50914", 0.5, 0.5, 0.5)
    return text(img, "NETFLIX", 320, 180, 78, 700, fill=(229, 9, 20, 255), spacing=7)


def tile_hotstar():
    img = base("#06103a", "#1f45b5", "#4f7bff")

    def go(d):
        cx, cy, r = 392, 104, 20
        pts = []
        for i in range(8):
            rad = r if i % 2 == 0 else r * 0.28
            a = math.radians(i * 45 - 90)
            pts.append(((cx + rad * math.cos(a)) * SS, (cy + rad * math.sin(a)) * SS))
        d.polygon(pts, fill=(255, 255, 255, 255))
    img = draw_on(img, go)
    img = text(img, "Disney+", 300, 112, 36, 400, fill=(220, 230, 255, 235), shadow=False)
    return text(img, "hotstar", 320, 222, 92, 600)


def tile_purple():
    img = base("#190a4a", "#6a2cff", "#b48cff")
    img = tv_glyph(img, 320, 128, 124, (255, 255, 255, 255))
    return text(img, "PURPLE IPTV", 320, 270, 44, 600, spacing=4)


def tile_iptv():
    img = base("#0d0d12", "#30303a", "#ff4d4d", 0.3, 0.2, 0.35)
    img = text(img, "IPTV", 320, 160, 104, 700, spacing=6)

    def go(d):
        d.rounded_rectangle(S(250, 236, 390, 244), 4 * SS, fill=(255, 64, 64, 255))
    return draw_on(img, go)


def tile_live():
    img = base("#04384f", "#14a9e6", "#6fe0ff")
    img = tv_glyph(img, 320, 128, 124, (255, 255, 255, 255))
    return text(img, "Live Channels", 320, 272, 44, 500, spacing=1)


def tile_play():
    img = base("#0b1b28", "#24465f", "#4aa3ff")

    def go(d):
        cx, cy, s = 320, 128, 92
        a, b, c, m = (cx - s * 0.78, cy - s), (cx - s * 0.78, cy + s), (cx + s * 0.95, cy), (cx - s * 0.1, cy)
        d.polygon(S(*a, *b, *m), fill=(0, 214, 255, 255))
        d.polygon(S(*a, *m, *c), fill=(0, 230, 118, 255))
        d.polygon(S(*b, *c, *m), fill=(255, 64, 90, 255))
    img = draw_on(img, go)
    return text(img, "Play Store", 320, 274, 46, 500)


def tile_prime():
    img = base("#04122a", "#0a58b8", "#25b6ff", 0.3, 0.25, 0.5)
    img = text(img, "prime video", 320, 160, 58, 600)

    def go(d):
        d.arc(S(226, 168, 414, 284), 22, 158, fill=(0, 183, 255, 255), width=9 * SS)
        d.polygon(S(406, 226, 424, 212, 421, 236), fill=(0, 183, 255, 255))
    return draw_on(img, go)


def tile_vlc():
    img = base("#2a1100", "#b85200", "#ffb347")

    def go(d):
        d.polygon(S(320, 70, 408, 214, 232, 214), fill=(255, 140, 0, 255))
        d.polygon(S(296, 128, 344, 128, 358, 152, 282, 152), fill=(255, 255, 255, 255))
        d.polygon(S(266, 176, 374, 176, 390, 200, 250, 200), fill=(255, 255, 255, 255))
        d.rounded_rectangle(S(222, 214, 418, 238), 10 * SS, fill=(255, 106, 0, 255))
    img = draw_on(img, go)
    return text(img, "VLC", 320, 292, 46, 600, spacing=6)


def tile_settings():
    img = base("#101a26", "#3b4f66", "#9fb8d6", 0.3, 0.2, 0.4)

    def go(d):
        cx, cy = 320, 128
        d.ellipse(S(cx - 50, cy - 50, cx + 50, cy + 50), fill=(255, 255, 255, 255))
        for i in range(8):
            a = math.radians(i * 45)
            ux, uy = math.cos(a), math.sin(a)
            px, py = -uy, ux
            pts = [(cx + ux * 42 + px * 15, cy + uy * 42 + py * 15), (cx + ux * 66 + px * 12, cy + uy * 66 + py * 12),
                   (cx + ux * 66 - px * 12, cy + uy * 66 - py * 12), (cx + ux * 42 - px * 15, cy + uy * 42 - py * 15)]
            d.polygon([(x * SS, y * SS) for x, y in pts], fill=(255, 255, 255, 255))
        d.ellipse(S(cx - 22, cy - 22, cx + 22, cy + 22), fill=(0, 0, 0, 0))
    img = draw_on(img, go)
    return text(img, "Settings", 320, 274, 46, 500)


def monogram(img, cx, cy, r):
    def ring(d):
        d.ellipse(S(cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, 26), outline=(255, 255, 255, 120), width=3 * SS)
    img = draw_on(img, ring)
    return text(img, OWNER[:1].upper(), cx, cy - r * 0.04, int(r * 1.25), 600)


def tile_hub():
    img = base("#08121f", "#274a6e", "#f3e6c8", 0.3, 0.2, 0.4)
    img = monogram(img, 320, 128, 96)
    return text(img, f"{OWNER}'s TV", 320, 276, 44, 400, spacing=3)


def icon_hub():
    size = 512
    ys, xs = np.mgrid[0:size * SS, 0:size * SS].astype(np.float32)
    t = ((xs + ys) / (2 * size * SS))[..., None]
    arr = rgb("#08121f") * (1 - t) + rgb("#2d527a") * t
    d2 = ((xs - 0.3 * size * SS) ** 2 + (ys - 0.2 * size * SS) ** 2) / (0.5 * size * SS) ** 2
    arr = arr + rgb("#f3e6c8") * (np.exp(-d2) * 0.35)[..., None]
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse((-0.3 * img.width, -1.2 * img.height, 1.3 * img.width, 0.5 * img.height), fill=(255, 255, 255, 40))
    img = Image.alpha_composite(img, layer.filter(ImageFilter.GaussianBlur(8 * SS)))
    cx = cy = size / 2
    r = 170
    ring = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse(S(cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, 28), outline=(255, 255, 255, 140), width=5 * SS)
    img = Image.alpha_composite(img, ring)
    f = font(int(r * 1.3), 600)
    letter = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(letter).text((cx * SS, (cy - 6) * SS), OWNER[:1].upper(), font=f, fill=(255, 255, 255, 255), anchor="mm")
    img = Image.alpha_composite(img, letter)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width, img.height), 110 * SS, fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


TILES = {
    "youtube": tile_youtube, "netflix": tile_netflix, "hotstar": tile_hotstar, "purple": tile_purple,
    "iptv": tile_iptv, "live": tile_live, "play": tile_play, "prime": tile_prime, "vlc": tile_vlc, "hub": tile_hub, "settings": tile_settings,
}


def finish(img):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), RADIUS * SS, fill=255)
    img = img.copy()
    img.putalpha(mask)
    return img.resize((W, H), Image.LANCZOS)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in TILES.items():
        finish(fn()).save(OUT / f"banner_{name}.png", optimize=True)
        print("tile", name)
    icon_hub().save(OUT / "ic_hub.png", optimize=True)
    print("icon ic_hub")
    return 0


if __name__ == "__main__":
    sys.exit(main())
