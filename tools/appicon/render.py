"""Render the SHUTTL. wordmark into app-icon art.

One source of truth for both platforms: the same letterforms (Archivo Bold, the
app's own typeface) laid out on a square, then exported at every size Android
and iOS ask for.

The mark is positioned on its *ink*, not on font advance widths. "S." ends in a
period whose right sidebearing is most of its advance, so centring the advance
box leaves the visible mark sitting noticeably left of centre.
"""
from PIL import Image, ImageDraw, ImageFont
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
FONT = REPO / "iosApp/Resources/Fonts/Archivo-Bold.ttf"

INK   = (11, 12, 13)      # ShuttlPalette.textHeading light / the wordmark's own black
PAPER = (255, 255, 255)

_PT = 400                 # layout size; the ink is rescaled to the target anyway
_SS = 4                   # supersample, so Archivo's stems stay crisp


def _ink(text, tracking_ratio):
    """The tracked run rendered as a tight alpha mask, cropped to its ink."""
    font = ImageFont.truetype(str(FONT), _PT)
    tracking = _PT * tracking_ratio
    pad = _PT
    canvas = Image.new("L", (pad * 2 + int(font.getlength(text)) + _PT, _PT * 3), 0)
    d = ImageDraw.Draw(canvas)
    x = pad
    for i, ch in enumerate(text):
        d.text((x, _PT), ch, font=font, fill=255)
        x += font.getlength(ch) + (tracking if i < len(text) - 1 else 0)
    return canvas.crop(canvas.getbbox())


def draw_wordmark(size, text, fg, bg, width_ratio, tracking_ratio=-0.02):
    """The mark centred on a square, its ink spanning width_ratio of the canvas.

    bg=None draws onto a transparent canvas, which is what the adaptive
    foreground needs. Subtracting a near-black tile from a flat render instead
    would leave its luminance behind as a haze over the whole layer.
    """
    S = size * _SS
    ink = _ink(text, tracking_ratio)
    w = round(S * width_ratio)
    h = max(1, round(ink.size[1] * w / ink.size[0]))
    ink = ink.resize((w, h), Image.LANCZOS)

    img = (Image.new("RGBA", (S, S), (0, 0, 0, 0)) if bg is None
           else Image.new("RGB", (S, S), bg))
    img.paste(Image.new("RGBA", ink.size, (*fg, 255)),
              ((S - w) // 2, (S - h) // 2), ink)
    return img.resize((size, size), Image.LANCZOS)


def ink_aspect(text, tracking_ratio=-0.02):
    """height / width of the mark's ink, for fitting it inside a circle."""
    w, h = _ink(text, tracking_ratio).size
    return h / w


def circle_mask(img):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, img.size[0] - 1, img.size[1] - 1), fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, mask=m)
    return out


def squircle_preview(img, radius_ratio=0.2237):
    """Approximate iOS's rounded-rect mask, for review only."""
    m = Image.new("L", img.size, 0)
    r = int(img.size[0] * radius_ratio)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1),
                                        radius=r, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, mask=m)
    return out
