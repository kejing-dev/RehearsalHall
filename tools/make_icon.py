"""Rehearsal Hall icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the adaptive icon
(`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python -I tools/make_icon.py [export.png]

Layer 0: the velvet-red tile. Layer 1: a cream spotlight beam falling onto a small lectern.
sdf_N = the layer's alpha in white. Coordinates below are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
VELVET = (142, 36, 51, 255)
CREAM = (255, 231, 194, 255)
BEAM = (255, 231, 194, 115)


def p(x, y):
    return (x * K, y * K)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=VELVET)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    beam = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(beam).polygon([p(50, 14), p(58, 14), p(80, 80), p(28, 80)], fill=BEAM)
    im = Image.alpha_composite(im, beam)
    ImageDraw.Draw(im).polygon([p(38, 64), p(70, 64), p(70, 70), p(63, 70), p(63, 88), p(45, 88), p(45, 70), p(38, 70)], fill=CREAM)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        Image.alpha_composite(l0, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
