#!/bin/bash
#
# Stages the ONNX graphs the on-device analysis runs into the app bundle.
#
# The mirror of androidApp/build.gradle.kts' verifyOnnxModels + copyOnnxModels
# pair, and it exists for the same reason: tools/models/onnx is gitignored
# derived output, so the graphs cannot be committed beside the code that loads
# them, and a build that quietly omits them produces an app that installs, runs
# and then fails at the first inference.
#
# Three graphs, not Android's four. inpaintnet is bundled over there and loaded
# by nothing - TrackNetRunner's KDoc names an InpaintNetRunner that was never
# written, and the pipeline design's section 6.3 records why it was dropped
# (it filled zero frames on both measured videos, and production's own PyTorch
# path fills zero too). Bundling a graph this app cannot run would be 2MB of
# cargo.
#
# ONNX_MODELS_OPTIONAL=YES downgrades the failure to a warning, matching
# Android's -PonnxModelsOptional=true. CI passes it because exporting the graphs
# needs the Modal weights and torch, neither of which a runner has. An app built
# that way cannot analyse anything, so do not set it for a build anyone intends
# to install.
set -euo pipefail

MODELS=(tracknet.fp16.onnx badminton.fp16.onnx posen.fp16.onnx)
SOURCE="$SRCROOT/../tools/models/onnx"
DEST="$BUILT_PRODUCTS_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/models"

# `set -u` catches an UNSET variable; it does not catch an empty one, and this
# script runs `rm -rf "$DEST"`. An empty BUILT_PRODUCTS_DIR would make that
# `rm -rf /models`.
if [ -z "${BUILT_PRODUCTS_DIR:-}" ] || [ -z "${UNLOCALIZED_RESOURCES_FOLDER_PATH:-}" ]; then
  echo "error: BUILT_PRODUCTS_DIR or UNLOCALIZED_RESOURCES_FOLDER_PATH is empty; refusing to stage"
  exit 1
fi

# A plain string, not an array: /bin/bash on macOS is 3.2, where an empty array
# under `set -u` is an unbound variable and `${#missing[@]}` exits the script
# before it can report anything.
missing=""
missing_count=0
for model in "${MODELS[@]}"; do
  if [ ! -f "$SOURCE/$model" ]; then
    missing="$missing $model"
    missing_count=$((missing_count + 1))
  fi
done

if [ "$missing_count" -ne 0 ]; then
  problem="missing ONNX graphs in $SOURCE:$missing
Run: python tools/models/pull_weights.py --tracker-repo ../badminton-tracker
then: python tools/models/export_tracknet.py --tracker-repo ../badminton-tracker
then: python tools/models/export_yolo.py"
  if [ "${ONNX_MODELS_OPTIONAL:-NO}" = "YES" ]; then
    echo "warning: $problem"
    echo "warning: building anyway: ONNX_MODELS_OPTIONAL=YES was set."
  else
    echo "error: $problem"
    exit 1
  fi
fi

# The destination is emptied first rather than copied over, for the reason
# androidApp uses a Sync task rather than a Copy: a copy leaves whatever is
# already staged alone, so renaming a graph ships the old file beside the new
# one. That is not hypothetical - the InpaintNet fp16-to-fp32 switch kept
# shipping the broken graph for a while over there.
rm -rf "$DEST"
mkdir -p "$DEST"
for model in "${MODELS[@]}"; do
  if [ -f "$SOURCE/$model" ]; then
    cp -f "$SOURCE/$model" "$DEST/$model"
  fi
done

# The Phase 1 model identity, derived from the pinned weight SHAs exactly as
# androidApp/build.gradle.kts derives BuildConfig.MODEL_VERSION: the first 8 hex
# of tracknet, inpaintnet and badminton, in that order, joined by dashes.
#
# It has to be byte-identical to Android's. The pipeline design's section 5.4
# re-anchoring rule keys on this string to decide whether a re-analysis has
# moved a clip boundary far enough to drag its annotations along, and two
# phones disagreeing about the name of the same weights would re-anchor
# annotations that never moved.
#
# Written as a file beside the graphs rather than baked into Info.plist, so the
# identity travels with the thing it identifies: a bundle staged with
# ONNX_MODELS_OPTIONAL=YES has no graphs and gets no version either, and the app
# can tell that apart from a version it simply failed to read.
#
# inpaintnet is in the derivation but not in MODELS. That is not a slip: the
# string names the WEIGHTS the Phase 1 pipeline was pinned against, and it must
# not change on iOS just because iOS declines to bundle a graph neither platform
# runs. Dropping the term here would make every iPhone re-anchor every
# annotation on its first re-analysis.
MANIFEST="$SRCROOT/../tools/models/manifest.json"
if [ -f "$MANIFEST" ] && [ "$missing_count" -eq 0 ]; then
  /usr/bin/python3 - "$MANIFEST" "$DEST/model-version.txt" <<'PY'
import json, sys
manifest, out = sys.argv[1], sys.argv[2]
weights = json.load(open(manifest))["weights"]
version = "-".join(
    (weights.get(name) or {}).get("sha256", "missing")[:8]
    for name in ("tracknet", "inpaintnet", "badminton")
)
open(out, "w").write(version)
PY
else
  rm -f "$DEST/model-version.txt"
fi
exit 0
