# Decode and preprocessing parity, Galaxy S23

Measured 2026-09-01 against `743d7fb1` (1920x1080, h264, 5972 frames), 24 frames
sampled evenly across the video. Reference produced by
`tools/models/check_decode_parity.py`; device side is
`FramePreprocessorTest` on an SM-S911B.

Design section 5.4 named this an unmeasured fidelity surface feeding every model
on every frame, and asked for it to be pinned before the device layer was built.
This is that measurement.

## Result

**Mean absolute difference 1.56/255, max 4/255**, against a threshold of 2/255.
Later shuttle drift, if any appears, is not a decode artifact.

## The colour conversion is not what the file says it is

The container declares `color_space=bt709`, `color_transfer=bt709`,
`color_primaries=bt709`, `color_range=tv`. Production does not decode it that
way. All four combinations, same frames:

| variant | mean | max |
|---|---|---|
| BT.601 full range | 6.55/255 | 19/255 |
| **BT.601 limited range** | **1.56/255** | **4/255** |
| BT.709 full range | 8.05/255 | 33/255 |
| BT.709 limited range | 3.45/255 | 22/255 |

OpenCV's `VideoCapture` converts YUV to BGR without handing the container's
colour space to swscale, which falls back to BT.601. So the cloud decodes a
BT.709 file with BT.601 coefficients, and matching the cloud means doing the
same.

Worth stating plainly because it looks like a bug: **the file's own metadata is
the wrong guide here.** Two reasonable guesses came before this measurement -
BT.601 full range, then BT.709 limited on the strength of the metadata - and
both were wrong. Only measuring all four found it.

If the cloud ever moves off OpenCV to something colour-aware, this default
becomes wrong in exactly the same quiet way.
`report_every_colour_conversion_against_production` is the test that would say
so, and it prints all four every run rather than only judging one.

## Frame count

Container metadata, OpenCV decode, MediaCodec decode and the cloud's
`results.json` `total_frames` all agree at **5972**, and fps matches to full
precision. Section 5.4 lists a differing frame count as a risk; it does not
materialise on this file or this device.
