# App icon

`generate.py` renders the launcher icon for both platforms from the SHUTTL.
wordmark and writes it straight into `androidApp/` and `iosApp/`. The PNGs in
those trees are generated - change this script and re-run it, don't hand-edit
them.

```sh
python3 tools/appicon/generate.py     # needs Pillow
```

The mark is `S.`, the wordmark's first letter plus its trailing period, set in
Archivo Bold - the typeface the app already bundles. The full `SHUTTL.` is not
usable as an icon: Android's adaptive mask only guarantees the inner 66dp of a
108dp canvas, so at a 48dp launcher size seven glyphs get about 4dp each and
stop being readable. `generate.py` asserts the mark's ink fits that circle.

White on `#0B0C0D`, the same near-black as the wordmark's ink and as
`ShuttlPalette.textHeading` in light / `bg` in dark.

What it writes:

| Path | |
|---|---|
| `mipmap-*/ic_launcher_foreground.png` | adaptive foreground, mark on alpha |
| `mipmap-*/ic_launcher_monochrome.png` | themed icons (Android 13+), same silhouette |
| `mipmap-*/ic_launcher{,_round}.png` | legacy, unused at minSdk 26 but kept in step |
| `AppIcon.appiconset/AppIcon.png` | iOS 1024, opaque - App Store rejects alpha |
| `tools/appicon/play-store-512.png` | Play Console listing icon |

The Play Console icon is **not** read from the APK - it is a separate upload in
the console's store listing, so changing the launcher icon alone leaves the old
mark on the store page. Re-upload `play-store-512.png` after any change here.
App Store Connect does take the 1024 from the asset catalog, so iOS needs
nothing extra.

The tile colour lives in `drawable/ic_launcher_background.xml`, not here.
