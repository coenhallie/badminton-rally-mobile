# Phase 1 on-device throughput, Galaxy S23

Measured 2026-09-01, SM-S911B (Snapdragon 8 Gen 2), Android 16, on the
`743d7fb1` corpus video at 1920x1080.

## Per-frame cost

| stage | ms/frame |
|---|---|
| MediaCodec decode | 9.3 |
| YUV to RGB, fused with the resize | 58.4 |
| TrackNet inference (CPU) | **161.2** |
| heatmap to coordinate | 5.5 |
| **total** | **235.1** |

TrackNet is 69% of it. Everything else together is 73ms.

## What that means

- The 3.3-minute corpus video (5972 frames): **23 minutes**, about 7.0x realtime.
- A 30-minute match at 30fps (54,000 frames): **3.5 hours**.

**Phase 1 is not viable on device at this speed**, on a current flagship, and
this is Phase 1 alone - pose is Phase 2 and costs more again. Section 5.6's
routing threshold is a multiple of video duration; at 9.4x nearly everything
routes to the cloud, which is the outcome the on-device work exists to avoid.

## Execution providers: acceleration made it worse

| provider | ms/frame |
|---|---|
| **CPU** | **161.2** |
| NNAPI | 156.9 |
| XNNPACK | 439.8 |

(Measured before the boxing fix below the numbers were 233 / 277 / 662; the
ordering was the same and XNNPACK was worse by the same factor.)

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

## Batching: tried, and it does nothing

This was the largest untested lever, on the reasoning that production runs
`batch_size=16` while this runner does one sequence per call. TrackNet was
re-exported with a dynamic batch axis and measured on both machines:

| batch | desktop ms/frame | S23 ms/frame |
|---|---|---|
| 1 | 43.7 | 157.9 |
| 2 | 43.5 | 187.4 |
| 4 | 42.8 | 154.0 |
| 8 | 43.1 | - |

Flat. The model is compute-bound, not launch-overhead-bound, so there is
nothing for a batch to amortise. The dynamic axis was reverted: it bought
nothing and an unused degree of freedom in a shipped graph is not free to
reason about.

**The experiment was still worth running**, because it found the boxing bug.
`OnnxSession` flattened ONNX Runtime's nested output arrays with
`flatMap { it.asIterable() }`, which boxes every element - 1.2M `Float` objects
per inference at batch 1, and an outright OOM at batch 4 on a 256MB heap.
Copying into a preallocated array took TrackNet from 233ms to 161ms per frame,
a 31% improvement, and it was inside the measurement the whole time.

## int8: tried, and it fails on accuracy - but the arithmetic matters more

Section 8 deferred int8 "until fp16 numbers exist to compare against", calling
it "the change that would trigger ref section 8.1's distance inflation". Those
numbers exist now, so it was measured. Calibrated statically on 24 sequences of
real frames spread across the video, using production's own preprocessing and
median background.

| model | size | ms/frame (desktop) | peaks moved vs PyTorch |
|---|---|---|---|
| fp16 | 22.7 MB | 42.6 | **0 of 256** |
| int8 | 11.4 MB | 29.8 | **133 of 256 (52%)**, max shift 279 px |
| int8, output head left in float | 11.9 MB | 33.8 | **137 of 256 (54%)**, max shift 262 px |

30% faster and unusable. Keeping the predictor convolution and the final
up-block in float changed nothing, which says the loss is spread through the
U-Net rather than concentrated in the output head, so a third variant was not
attempted.

**The more useful conclusion is that it would not have mattered.** At its
measured 30% saving on the dominant stage, a perfectly accurate int8 gives:

| | ms/frame | 3.3-min video | 30-min match | realtime |
|---|---|---|---|---|
| fp16, today | 235.1 | 23.4 min | 3.5 h | 7.1x |
| int8, had it worked | 186.7 | 18.6 min | 2.8 h | 5.6x |

A 1x-realtime pipeline needs **33.3 ms/frame**. TrackNet alone costs 161ms.
The gap is not 30% wide, it is roughly 7x, and no combination of the levers on
this list closes it:

- **Acceleration**: measured, no gain (CPU 161, NNAPI 157, XNNPACK 440).
- **Batching**: measured, no gain; the model is compute-bound.
- **int8**: 30% at best, and it destroys the track.
- **A smaller input**: unmeasured. 512x288 is production's choice. Halving each
  dimension is a 4x reduction in convolution work, which is the only remaining
  lever of the right order - and it changes what the model sees, so it needs an
  accuracy measurement of its own against the coverage gate.
- **A different runtime**: section 8 keeps native LiteRT as an escape hatch
  "only justified if section 7's numbers demand it". These numbers demand at
  least the experiment.

## What this means for the design

On-device Phase 1 does not currently pay for itself on a current flagship
Android. Section 5.6's routing threshold is a multiple of video duration; at
7.1x essentially every video routes to the cloud, which is the outcome the
whole on-device effort exists to avoid.

This does not invalidate the ported `:analysis` layer, which is
platform-independent, verified stage by stage against the cloud's own
detectors, and would be needed by any device-side pipeline. The problem is
narrowly TrackNet inference throughput.
