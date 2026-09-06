# Vendored upstream licences

## TrackNetV3 (`TrackNetV3-LICENSE.txt`)

Source: https://github.com/qaz812345/TrackNetV3, `master`, retrieved 2026-09-01.
Covers `tracknet.pt` and `inpaintnet.pt` in `tools/models/manifest.json`.

**MIT, and the checkpoints are explicitly included.** This is the question
design section 3.5 flagged as capable of forcing the models out of the app
bundle and into a Supabase `models` bucket. It does not.

The licence text is MIT but **not verbatim**: the grant has been widened from
"this software" to "this software, pretrained models and associated
documentation files". That edit is deliberate, and the upstream README says
the same thing in prose under its License heading - "This project, including
both the codebase and the pretrained model checkpoints (e.g., weights hosted
on Google Drive), is released under the MIT License... free to use, modify,
and distribute it for both non-commercial and commercial purposes."

Note GitHub's own API reports `NOASSERTION` / `Other` for this repository.
That is its classifier declining to match a modified template, not a
restriction: the modification widens the grant rather than narrowing it.

**What this obliges us to do.** MIT requires the copyright notice and
permission notice to travel with redistributed copies. Shipping these weights
inside the app is redistribution, so the app needs an attribution entry
carrying this text. That is a shipping task, not a conversion one, and it is
not yet done.
