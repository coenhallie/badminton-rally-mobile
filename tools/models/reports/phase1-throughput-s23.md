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

## What that means, against the videos that actually exist

An earlier version of this report called Phase 1 "not viable on device",
reasoning from the 30-minute 30fps match section 5.6 uses as its example. That
was the wrong denominator. Every video in the production `videos` table,
2026-09-01:

| | duration |
|---|---|
| median | **1.0 min** |
| mean | 2.6 min |
| longest | 8.0 min |

There is no 30-minute video. At 235ms per frame:

| duration | frames | on-device |
|---|---|---|
| 0.8 min | 1,350 | 5.3 min |
| 1.0 min (median) | 1,800 | **7.1 min** |
| 3.3 min | 5,972 | 23.4 min |
| 6.3 min at 50fps | 18,969 | 74.3 min |
| 8.0 min | 12,032 | 47.1 min |

**So the median upload is about seven minutes of background processing**, which
is a product decision rather than an impossibility. The tail is the problem:
two 6.3-minute videos cost 74 minutes each, because they are 50fps and frame
count, not duration, is what this pipeline pays for.

That last point is worth stating plainly, since section 5.6 frames its
threshold as "a multiple of video duration": **duration is the wrong unit.** A
6.3-minute 50fps video has more frames than an 8-minute 25fps one and costs
more to analyse. The routing threshold should be expressed in frames.

Caveat on the sample: 11 videos with usable metadata, all from development and
testing rather than from clients. It establishes that the 30-minute assumption
was wrong, not what real usage looks like.

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

## Decision, 2026-09-01: accuracy over speed

Recorded because it closes the optimisation thread rather than leaving it
looking unfinished.

**No further speed work that costs accuracy.** int8 is measured, rejected, and
should not be revisited on speed grounds: it moves half the heatmap peaks. The
remaining lever of the right order was a smaller model input, which trades the
same currency, so it is not being pursued either. fp16 stays - it moves zero
peaks over 256 real frames on two videos.

That leaves throughput where it is, and against real video lengths that is a
defensible place for it to be. What it does change is the routing design:
section 5.6 should express its threshold **in frames rather than in a multiple
of duration**, because a 50fps clip costs twice a 25fps one of the same length.

None of this touches the ported `:analysis` layer, which is
platform-independent, verified stage by stage against the cloud's own
detectors, and needed by any device-side pipeline.

## End to end on the device, 2026-09-01

The whole Stage 1 path ran on an SM-S911B: decode, TrackNet, ROI and
static-cluster filtering, shot detection, both rally detectors, union,
refinement, clip padding, and MediaCodec clip cutting.

```
frames=320  shuttleVisible=94 (29.4%)  rallies=1  clips=1
```

320 frames is 10.7 seconds of the corpus video, bounded so an instrumented run
finishes. `RawInference` also round-tripped through its codec, so what the
engine writes is what `:analysis` reads.

The run took 12m22s for those 320 frames, far above the 235ms per frame the
stage timings predict. The gap is the **median background pre-pass**: 300
`getFrameAtIndex` seeks is a fixed cost regardless of how many frames are then
analysed, so on a 320-frame run it dominates and on a full video it is
amortised across thousands. It is not a new performance problem, but it does
mean short runs cannot be used to estimate long ones.

**What this does not yet prove.** The detector is not wired in, so the fusion
track and the TrackNet track are the same and rally counts are not a parity
result against the cloud. And clips land in app storage rather than Supabase.
