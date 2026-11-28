"""Rehearsal Hall icon (v2, 2026-11-27): a microphone in a spotlight on a velvet-red stage, listeners' heads in front.

    python -I tools/make_icon.py [export.png]

Writes the PICO 3D icon layers (layer 0 = the velvet tile, layer 1 = beam + microphone + heads; sdf_N = the layer's
alpha in white), the adaptive icon's background / foreground PNGs, and optionally a 512 export.
Coordinates are a 108-unit viewport (the adaptive icon's), drawn at 4x and scaled down for smooth edges.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SS = 2  # supersampling
W = S * SS
K = W / 108.0
TOP = (176, 48, 66)
BOTTOM = (84, 16, 30)
CREAM = (255, 236, 204, 255)
GOLD = (233, 184, 112, 255)
SHADE = (196, 142, 84, 255)
DARK = (44, 8, 18, 255)
RIM = (150, 44, 60, 255)


def u(v):
    return v * K


def box(x0, y0, x1, y1):
    return (u(x0), u(y0), u(x1), u(y1))


def background():
    """Velvet: top-lit vertical gradient, soft curtain folds, darker corners. Full bleed."""
    im = Image.new("RGB", (W, W))
    px = ImageDraw.Draw(im)
    for y in range(W):
        t = y / (W - 1)
        px.line([(0, y), (W, y)], fill=tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t ** 0.9) for i in range(3)))
    folds = Image.new("L", (W, W), 0)
    fd = ImageDraw.Draw(folds)
    for x in (8, 21, 33, 75, 87, 100):
        fd.rectangle(box(x - 2.2, 0, x + 2.2, 108), fill=70)
    folds = folds.filter(ImageFilter.GaussianBlur(u(2.6)))
    im = Image.composite(Image.new("RGB", (W, W), (52, 8, 20)), im, folds)
    vig = Image.new("L", (W, W), 120)
    ImageDraw.Draw(vig).ellipse(box(-10, -24, 118, 104), fill=0)
    vig = vig.filter(ImageFilter.GaussianBlur(u(14)))
    im = Image.composite(Image.new("RGB", (W, W), (40, 6, 16)), im, vig)
    return im.convert("RGBA")


def foreground():
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    # spotlight: a soft cone from the top and its pool on the floor
    cone = Image.new("L", (W, W), 0)
    ImageDraw.Draw(cone).polygon([(u(50), u(0)), (u(58), u(0)), (u(84), u(80)), (u(24), u(80))], fill=255)
    fade = Image.new("L", (W, W), 0)
    fd = ImageDraw.Draw(fade)
    for y in range(W):
        t = y / (W - 1)
        fd.line([(0, y), (W, y)], fill=int(150 - 95 * min(1.0, t / 0.75)))
    cone = Image.composite(fade, Image.new("L", (W, W), 0), cone).filter(ImageFilter.GaussianBlur(u(1.6)))
    im.paste(Image.new("RGBA", (W, W), (255, 232, 190, 255)), (0, 0), cone)
    pool = Image.new("L", (W, W), 0)
    ImageDraw.Draw(pool).ellipse(box(22, 72, 86, 88), fill=120)
    pool = pool.filter(ImageFilter.GaussianBlur(u(2.2)))
    im = Image.alpha_composite(im, Image.merge("RGBA", (*Image.new("RGB", (W, W), (255, 226, 176)).split(), pool)))

    d = ImageDraw.Draw(im)
    # microphone: base, stem, cradle, head with a grille
    d.ellipse(box(42, 71.5, 66, 78.5), fill=SHADE)
    d.ellipse(box(42, 70.3, 66, 77.3), fill=CREAM)
    d.rounded_rectangle(box(52.4, 55, 55.6, 74), radius=u(1.2), fill=CREAM)
    d.arc(box(41, 30, 67, 58), start=10, end=170, fill=GOLD, width=int(u(3)))
    d.rounded_rectangle(box(52.2, 56.2, 55.8, 60), radius=u(1), fill=GOLD)
    d.rounded_rectangle(box(45.5, 20, 62.5, 51), radius=u(8.5), fill=CREAM)
    d.rounded_rectangle(box(45.5, 41.5, 62.5, 45), radius=u(0.4), fill=GOLD)
    for y in (26, 30.5, 35):
        d.rounded_rectangle(box(49, y, 59, y + 1.9), radius=u(0.95), fill=SHADE)
    d.rounded_rectangle(box(47.2, 23.5, 48.9, 38), radius=u(0.85), fill=(255, 250, 240, 255))

    # listeners: five heads and shoulders in the dark, a rim of stage light on top
    for cx, cy, r in ((16, 91, 7.4), (35, 94, 7.8), (54, 92, 7.6), (73, 94, 7.8), (92, 91, 7.4)):
        d.ellipse(box(cx - r, cy - r - 0.9, cx + r, cy + r - 0.9), fill=RIM)
        d.ellipse(box(cx - r, cy - r, cx + r, cy + r), fill=DARK)
        d.rounded_rectangle(box(cx - r * 1.75, cy + r * 0.95 - 0.9, cx + r * 1.75, 130), radius=u(r * 1.1), fill=RIM)
        d.rounded_rectangle(box(cx - r * 1.75, cy + r * 0.95, cx + r * 1.75, 130), radius=u(r * 1.1), fill=DARK)
    return im


def tile_mask():
    m = Image.new("L", (W, W), 0)
    ImageDraw.Draw(m).rounded_rectangle((40 * SS, 40 * SS, W - 40 * SS, W - 40 * SS), radius=220 * SS, fill=255)
    return m


def down(im, size=S):
    return im.resize((size, size), Image.LANCZOS)


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    bg, fg, mask = background(), foreground(), tile_mask()
    clear = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    l0 = down(Image.composite(bg, clear, mask))
    l1 = down(Image.composite(fg, clear, mask))
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    # adaptive icon: full-bleed 108 dp layers at xxxhdpi (432 px); the launcher masks them
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    down(bg, 432).convert("RGB").save(os.path.join(nodpi, "ic_launcher_bg.png"))
    down(fg, 432).save(os.path.join(nodpi, "ic_launcher_fg.png"))
    if len(sys.argv) > 1:
        down(Image.alpha_composite(Image.composite(bg, clear, mask), Image.composite(fg, clear, mask)), 512).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
