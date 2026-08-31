"""Shared helpers for the tools/models/ scripts.

Not a tool on its own; imported by export_tracknet.py, check_tracknet_parity.py
and measure_shuttle_coverage.py so that frame decoding, normalisation, and
heatmap postprocessing are byte-identical wherever that identity matters - in
particular between the PyTorch and ONNX sides of the 0a conversion-fidelity
gate in measure_shuttle_coverage.py.
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import torch

WIDTH, HEIGHT, SEQ = 512, 288, 8
# The cloud thresholds a sigmoid heatmap at 0.5 to decide visibility; matching
# it here keeps any comparison about conversion or reimplementation fidelity,
# not about a new threshold.
VIS_THRESHOLD = 0.5

WEIGHTS_DEFAULT = Path("tools/models/weights/tracknet.pt")
ONNX_DEFAULT = "tools/models/onnx/tracknet.fp16.onnx"


def open_video(path) -> cv2.VideoCapture:
    """Open a video, failing loudly instead of silently yielding zero frames.

    cv2.VideoCapture does not raise on a bad path or an unreadable file; it
    returns a capture object that is simply never opened, and reading from it
    yields zero frames. Left unchecked, that looks identical to "the shuttle
    is never visible" downstream - a typo'd path would print a gate result
    instead of an error. Production checks this the same way
    (backend/tracknet/inference.py:243).
    """
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        cap.release()
        raise RuntimeError(f"could not open video (bad path or unreadable file): {path}")
    return cap


def load_torch_model(tracker_repo, weights_path=WEIGHTS_DEFAULT):
    """Load the TrackNetV3 PyTorch checkpoint. Returns (model, in_dim, seq_len)."""
    sys.path.insert(0, str(Path(tracker_repo) / "backend"))
    from tracknet.model import TrackNet

    # weights_only=False: torch 2.6+ defaults to True, which refuses to
    # unpickle this checkpoint because it carries param_dict (seq_len,
    # bg_mode) alongside the state dict, not just tensors. Do not drop this.
    ckpt = torch.load(str(weights_path), map_location="cpu", weights_only=False)
    params = ckpt.get("param_dict", {})
    seq_len = params.get("seq_len", 8)
    bg_mode = params.get("bg_mode", "concat")
    in_dim = (seq_len + 1) * 3 if bg_mode == "concat" else seq_len * 3
    model = TrackNet(in_dim=in_dim, out_dim=seq_len)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()
    return model, in_dim, seq_len


def decode_frame(frame: np.ndarray) -> np.ndarray:
    """BGR video frame -> CHW float32 RGB in [0, 1], resized to the model input size."""
    small = cv2.cvtColor(cv2.resize(frame, (WIDTH, HEIGHT)), cv2.COLOR_BGR2RGB)
    return small.transpose(2, 0, 1) / 255.0


def iter_batches(cap: cv2.VideoCapture, seq: int = SEQ):
    """Decode a whole video into background-concat batches of `seq` frames.

    Yields (start_frame_idx, stack, real_count):
      - stack is (1, (seq + 1) * 3, H, W) float32, ready to feed to either the
        PyTorch model or the ONNX session unchanged.
      - real_count is the number of genuine (non-padded) frames in this
        batch. It is always `seq`, except possibly for the final batch, which
        is padded by repeating its last real frame so the static-shape graph
        can still consume it; only the first `real_count` output planes then
        correspond to real input frames.

    The first decoded frame of the whole video stands in as the background
    plane for every batch, matching how the checkpoint was trained.
    """
    first = None
    buf = []
    frame_idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        chw = decode_frame(frame)
        if first is None:
            first = chw
        buf.append(chw)
        if len(buf) == seq:
            stack = np.concatenate([first] + buf, axis=0)[None].astype(np.float32)
            yield frame_idx - seq + 1, stack, seq
            buf = []
        frame_idx += 1

    if buf:
        start = frame_idx - len(buf)
        real = len(buf)
        while len(buf) < seq:
            buf.append(buf[-1])
        stack = np.concatenate([first] + buf, axis=0)[None].astype(np.float32)
        yield start, stack, real


def heatmap_to_positions(hm: np.ndarray, scale_w: float, scale_h: float, count=None) -> list:
    """Per-plane argmax -> [{x, y, visible}, ...].

    x/y are scaled from the model's 512x288 heatmap grid to a (scale_w,
    scale_h) target. Pass WIDTH/HEIGHT for raw model-space pixels (identity
    scale), or a video's original resolution to compare against coordinates
    recorded in that space.
    """
    n = count if count is not None else hm.shape[1]
    out = []
    for i in range(n):
        plane = hm[0, i]
        flat = int(np.argmax(plane))
        peak = float(plane.flat[flat])
        out.append({
            "x": (flat % WIDTH) * (scale_w / WIDTH),
            "y": (flat // WIDTH) * (scale_h / HEIGHT),
            "visible": peak >= VIS_THRESHOLD,
        })
    return out


def run_torch(model, stack: np.ndarray) -> np.ndarray:
    with torch.no_grad():
        return model(torch.from_numpy(stack)).numpy()


def run_onnx(sess, stack: np.ndarray) -> np.ndarray:
    return sess.run(None, {"frames": stack})[0]


def euclidean(a: dict, b: dict) -> float:
    return ((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2) ** 0.5


def percentile_sorted(sorted_values: list, p: float):
    """Nearest-rank percentile over an already-sorted list, or None if empty."""
    if not sorted_values:
        return None
    idx = min(int(len(sorted_values) * p), len(sorted_values) - 1)
    return sorted_values[idx]
