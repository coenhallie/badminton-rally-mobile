# Phase 1 and pose on iOS, first numbers

Measured 2026-09-09 on the **iPhone 17 Pro simulator**, Xcode 26.5, over a
60-frame synthetic 1920x1080 H.264 clip written by `TestVideo`. Reproduce with:

```
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Release -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -only-testing:iosAppTests/ThroughputMeasurementTests \
  CODE_SIGNING_ALLOWED=NO ENABLE_TESTABILITY=YES SWIFT_OPTIMIZATION_LEVEL=-O \
  OTHER_SWIFT_FLAGS='$(inherited) -D MEASURE_THROUGHPUT'
```

## What it says

| build | base ms/frame | base+pose ms/frame | pose alone |
|---|---|---|---|
| Debug (`-Onone`) | 318.8 | 523.1 | 204.3 |
| **Release (`-O`)** | **84.4** | **137.6** | **53.2** |
| S23 seed, for comparison | 235.0 | 465.0 | 230.0 |

## Three things this is not

**It is not a device measurement.** The simulator runs arm64 code on the Mac's
own CPU, with a desktop memory system and no thermal ceiling. `DeviceThroughput`
exists precisely because that spread is wider than any default covers, and its
`THROTTLE` constant of 1.6 came from watching an S23 climb from 1385ms to 2310ms
over eleven minutes. Nothing here can climb. **Do not seed anything from this
table.**

**It is not a decode measurement.** The clip is solid grey, which H.264
compresses to almost nothing, so the decoder does far less than on a match. On
the S23 decode was 9.3ms of 235; whatever the equivalent is here, this number
does not contain it.

**It is not comparable frame for frame with the S23 table**, which is broken
down by stage against real footage. This is wall clock over the whole engine.

## The one thing it does settle

**Build configuration dominates, by 3.8x on base.** The Swift preprocessing is a
per-pixel loop over 2.07 million source pixels per frame, and at `-Onone` every
array access is bounds-checked and nothing inlines. The models themselves are
ONNX Runtime's C++ and do not care about Swift's optimisation level, which is
why pose - almost pure inference - moves by 3.8x too only because the same
letterbox conversion runs ahead of it.

Consequence: **never quote a Debug figure**, and never let the app's own
`DeviceThroughputRepository` record one. A developer running a Debug build would
otherwise teach the estimator that this phone is four times slower than it is,
and the estimate persists.

## What still has to be measured

An actual iPhone, on the corpus video, base and base+pose separately, in a
Release build. Until then the metric picker on iOS quotes the S23 seeds, which
this table is enough to show are the wrong shape - not because they are too high
or too low, but because nobody has a number for the hardware in question.

That measurement also settles the design's section 4: foreground-only analysis
is a reasonable ask if a typical match is twenty minutes of work and an
unreasonable one if it is two hours, and those are both inside the range these
figures leave open.
