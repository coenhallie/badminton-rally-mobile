# Phase 1 on-device throughput, Galaxy S23

Measured 2026-09-01, SM-S911B (Snapdragon 8 Gen 2), Android 16, on the
`743d7fb1` corpus video at 1920x1080.

## Per-frame cost

| stage | ms/frame |
|---|---|
| MediaCodec decode | 9.2 |
| YUV to RGB, fused with the resize | 66.2 |
| TrackNet inference (CPU) | **232.9** |
| heatmap to coordinate | 7.0 |
| **total** | **315.3** |

TrackNet is 74% of it. Everything else together is 82ms.

## What that means

- The 3.3-minute corpus video (5972 frames): **31 minutes**, about 9.4x realtime.
- A 30-minute match at 30fps (54,000 frames): **4.7 hours**.

**Phase 1 is not viable on device at this speed**, on a current flagship, and
this is Phase 1 alone - pose is Phase 2 and costs more again. Section 5.6's
routing threshold is a multiple of video duration; at 9.4x nearly everything
routes to the cloud, which is the outcome the on-device work exists to avoid.

## Execution providers: acceleration made it worse

| provider | ms/frame |
|---|---|
| **CPU** | **232.9** |
| NNAPI | 277.1 |
| XNNPACK | 662.2 |

Measured on the same graph and input in one run. NNAPI is 19% slower and
XNNPACK 2.8x slower than the plain CPU provider, so the default is CPU. This
was worth measuring rather than assuming: "enable the accelerator" is the
obvious move and it is wrong here. Most likely the fp16 graph forces
conversions at every partition boundary, but that is a hypothesis and the
number is the fact.

## The fused conversion

Converting the full 1920x1080 frame to RGB and then resizing cost 231 + 9ms.
Sampling only the pixels the 512x288 output needs costs 66ms, a 3.6x
improvement on that stage, and the decode-parity threshold test still passes at
the same 2/255 bound. The bilinear resize reads four source pixels per output
pixel, so at most 590k of the 2.07M pixels were ever needed.

## Where to look next, in order of expected return

1. **Batch several sequences per inference.** Production runs `batch_size=16`
   (`inference.py`), this runner does one sequence at a time, and the exported
   graph has a static batch axis of 1 so it cannot do otherwise today.
   Re-exporting TrackNet with a dynamic batch axis is a small change to
   `export_tracknet.py` and is the largest untested lever.
2. **int8 quantization.** Section 8 defers it until fp16 numbers exist to
   compare against. They now do, on two videos, and the fp16 conversion is
   near-exact - so the comparison this was waiting for is available.
3. **A smaller input.** TrackNet runs at 512x288 because production does. What
   accuracy costs what time has never been measured.

Nothing here is worth doing before the batching experiment, which could move
the dominant 74% on its own.
