"""Write the SHUTTL. app icon into androidApp/ and iosApp/.

The mark is "S." - the wordmark's first letter plus its trailing period, set in
Archivo Bold, the app's own typeface. The full "SHUTTL." does not survive
Android's adaptive mask: only the inner 66/108 circle is guaranteed visible, so
at a 48dp launcher size seven glyphs get about 4dp each and turn to mush.
"""
import pathlib

from render import REPO, INK, PAPER, draw_wordmark, circle_mask, ink_aspect

MARK = "S."
FG, BG = PAPER, INK          # white mark on a near-black tile
FULL_RATIO = 0.46            # ink width, as a fraction of a full-bleed square

# Adaptive icons: 108dp canvas, ~72dp of it ever visible, 66dp guaranteed.
CANVAS, VISIBLE, SAFE = 108, 72, 66
ADAPTIVE_RATIO = FULL_RATIO * VISIBLE / CANVAS   # same optical size inside the mask

AND = REPO / "androidApp/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def assert_inside_safe_circle():
    """The mark's ink must fit the 66dp guaranteed circle."""
    w = ADAPTIVE_RATIO * CANVAS
    h = w * ink_aspect(MARK)
    diag = (w ** 2 + h ** 2) ** 0.5
    assert diag <= SAFE, f"mark diagonal {diag:.1f}dp exceeds the {SAFE}dp safe circle"
    print(f"  safe-circle check: {diag:.1f}dp of {SAFE}dp")


def main():
    assert_inside_safe_circle()

    for name, scale in DENSITIES.items():
        d = AND / f"mipmap-{name}"
        legacy = round(48 * scale)
        square = draw_wordmark(legacy, MARK, FG, BG, FULL_RATIO)
        square.save(d / "ic_launcher.png")
        circle_mask(square).save(d / "ic_launcher_round.png")

        # bg=None: the mark on a transparent canvas, so the layer adds nothing
        # but the glyphs. The tile colour comes from ic_launcher_background.
        fg_px = round(CANVAS * scale)
        alpha = draw_wordmark(fg_px, MARK, FG, None, ADAPTIVE_RATIO)
        alpha.save(d / "ic_launcher_foreground.png")
        # Themed icons (Android 13+): the system tints the alpha silhouette.
        alpha.save(d / "ic_launcher_monochrome.png")
        print(f"  android {name}: {legacy}px legacy, {fg_px}px adaptive")

    # App Store validation rejects an alpha channel on the 1024 marketing icon,
    # and the system applies the rounding, so this stays a full-bleed opaque square.
    ios = draw_wordmark(1024, MARK, FG, BG, FULL_RATIO).convert("RGB")
    ios.save(REPO / "iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
    print("  ios: 1024px, no alpha")

    # The Play Console listing icon is uploaded by hand and is NOT taken from
    # the APK, so it goes stale on its own unless it is generated here too.
    store = draw_wordmark(512, MARK, FG, BG, FULL_RATIO).convert("RGB")
    store.save(pathlib.Path(__file__).parent / "play-store-512.png")
    print("  play store: 512px, upload by hand")


main()
