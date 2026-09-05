#!/usr/bin/env bash
# Tap an Android UI element BY TEXT, never by a coordinate you worked out yourself.
#
# Why this exists: four times in this project an agent read a tap position off a
# scaled screenshot, forgot to convert back to device pixels, and hit the wrong
# control. Twice that control was "Sign out", and the user had to sign in by hand
# again. A written rule did not stop it, so this removes the arithmetic entirely.
#
#   tools/adb-tap.sh "Switch to dark mode"
#   tools/adb-tap.sh --contains "Analytics"
#   tools/adb-tap.sh --list          # dump every tappable label, tap nothing
#
# For genuinely unlabelled surfaces - marking court corners on a video frame,
# for example - tap a fraction INSIDE a labelled container. The coordinates
# still come from the uiautomator dump's real device pixels, never from a
# screenshot, so the arithmetic that caused four sign-outs stays impossible:
#
#   tools/adb-tap.sh --within "Court frame" --at 0.25,0.80
#
# Refuses to tap anything whose label matches a destructive pattern unless you
# pass --force-destructive, which you should not. For --within that guard is
# applied to the computed POINT as well as to the container's own label, because
# a benign container is no promise about what sits at the fraction: "Settings"
# at 0.5,0.9 can be "Sign out".
#
# tools/adb-tap-test.sh proves every one of those refusals still fires.
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
MATCH="exact"; FORCE=0; TARGET=""; WITHIN=""; AT=""
need_value() {
  # Without this, `set -u` turns a missing value into an unbound-variable crash
  # rather than a usage message, and the caller cannot tell the two apart.
  [ "$2" -ge 2 ] || { echo "$1 needs a value" >&2; exit 6; }
}
while [ $# -gt 0 ]; do
  case "$1" in
    --within) need_value --within $#; WITHIN="$2"; shift 2 ;;
    --at) need_value --at $#; AT="$2"; shift 2 ;;
    --contains) MATCH="contains"; shift ;;
    --force-destructive) FORCE=1; shift ;;
    --list) MATCH="list"; shift ;;
    *) TARGET="$1"; shift ;;
  esac
done

# --at only means anything inside a container. Accepting it alone would tap the
# label's centre while printing a success line that never mentions the fraction,
# which is the silent wrong coordinate this whole script exists to prevent.
if [ -n "$AT" ] && [ -z "$WITHIN" ]; then
  echo "--at needs --within LABEL. On its own it would be ignored and the centre tapped." >&2
  exit 6
fi

DUMP=$(mktemp); trap 'rm -f "$DUMP"' EXIT
"$ADB" shell uiautomator dump /sdcard/win.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/win.xml > "$DUMP" 2>/dev/null

python3 - "$DUMP" "$TARGET" "$MATCH" "$FORCE" "$WITHIN" "$AT" <<'PY'
import sys, subprocess, os, re
import xml.etree.ElementTree as ET

dump, target, mode, force = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1"
within, at = (sys.argv[5] if len(sys.argv) > 5 else ""), (sys.argv[6] if len(sys.argv) > 6 else "")
root = ET.parse(dump).getroot()

DESTRUCTIVE = r"sign\s*out|log\s*out|delete|remove|wipe|clear|erase|trash|discard|leave|unshare|revoke|reset"

# Parent map so a label can find the control that actually owns its tap.
# Compose usually marks an ANCESTOR clickable, not the text node itself, so
# demanding clickability on the matched node would refuse most real buttons.
parent = {c: p for p in root.iter() for c in p}

def bounds(n):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def label(n):
    return n.get("text") or n.get("content-desc") or ""

def tappable_ancestor(n):
    """The node itself if clickable, else the nearest clickable ancestor."""
    cur, hops = n, 0
    while cur is not None and hops <= 6:
        if cur.get("clickable") == "true" and bounds(cur):
            return cur
        cur = parent.get(cur)
        hops += 1
    return None

labelled = [n for n in root.iter() if label(n) and bounds(n)]

def raw_bounds(n):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None

if within:
    # A fraction inside a labelled container. Used for surfaces that genuinely
    # have no label of their own, such as marking corners on a video frame.
    # The container is still found by label in the dump, so the numbers are real
    # device pixels and never come from a scaled image.
    if not at:
        print("--within needs --at X,Y with fractions between 0 and 1", file=sys.stderr)
        sys.exit(6)
    try:
        fx, fy = (float(v) for v in at.split(","))
    except ValueError:
        print(f"--at {at!r} is not two numbers separated by a comma", file=sys.stderr)
        sys.exit(6)
    if not (0.0 <= fx <= 1.0 and 0.0 <= fy <= 1.0):
        print(f"--at {at!r} must be fractions between 0 and 1, not pixels", file=sys.stderr)
        sys.exit(6)
    if re.search(DESTRUCTIVE, within, re.I) and not force:
        print(f"REFUSED: container {within!r} looks destructive. "
              f"Pass --force-destructive if you truly mean it.", file=sys.stderr)
        sys.exit(4)
    # Same matching rule as the default path. Substring by default would have made
    # --within strictly looser than the path it bypasses: --within "Remove" would
    # have silently accepted "Remove from app".
    hosts = [n for n in labelled
             if (label(n) == within if mode != "contains" else within.lower() in label(n).lower())
             and raw_bounds(n)]
    if not hosts:
        print(f"NOT FOUND: no container labelled like {within!r}. Try --list.", file=sys.stderr)
        sys.exit(2)
    if len({raw_bounds(h) for h in hosts}) > 1:
        print(f"AMBIGUOUS: {len(hosts)} containers match {within!r}:", file=sys.stderr)
        for h in hosts:
            print(f"  {label(h)!r} bounds={raw_bounds(h)}", file=sys.stderr)
        sys.exit(3)
    x1, y1, x2, y2 = raw_bounds(hosts[0])
    cx, cy = int(x1 + (x2 - x1) * fx), int(y1 + (y2 - y1) * fy)
    # The host's own label says nothing about what sits at the fraction, so the
    # guard has to look at the point itself: --within "Settings" --at 0.5,0.9 can
    # land on "Sign out". Refuse if any labelled node covering the point is
    # destructive, whether or not it is the container.
    def covers(n, x, y):
        b = raw_bounds(n)
        return b is not None and b[0] <= x <= b[2] and b[1] <= y <= b[3]
    if not force:
        under = [label(n) for n in labelled
                 if covers(n, cx, cy) and re.search(DESTRUCTIVE, label(n), re.I)]
        if under:
            print(f"REFUSED: ({cx},{cy}) is inside {under[0]!r}, which looks destructive. "
                  f"Pass --force-destructive if you truly mean it.", file=sys.stderr)
            sys.exit(4)
    adb = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    subprocess.run([adb, "shell", "input", "tap", str(cx), str(cy)], check=True)
    print(f"tapped {fx},{fy} within {label(hosts[0])!r} at device pixels ({cx},{cy})")
    sys.exit(0)

if mode == "list":
    for n in labelled:
        t = tappable_ancestor(n)
        cx, cy = bounds(t) if t is not None else bounds(n)
        print(f"{'TAP' if t is not None else '   '}  {label(n)!r}  centre=({cx},{cy})")
    sys.exit(0)

matched = [n for n in labelled
           if (label(n) == target if mode == "exact" else target.lower() in label(n).lower())]
if not matched:
    print(f"NOT FOUND: {target!r}. Run with --list to see available labels.", file=sys.stderr)
    sys.exit(2)

# Collapse to distinct tap points: a label and its clickable parent are one control.
seen, hits = set(), []
for n in matched:
    t = tappable_ancestor(n)
    if t is None:
        continue
    pt = bounds(t)
    if pt not in seen:
        seen.add(pt)
        hits.append((label(n), pt))

if not hits:
    print(f"NOT TAPPABLE: {target!r} matched {len(matched)} node(s); none is clickable "
          f"nor has a clickable ancestor within 6 levels.", file=sys.stderr)
    sys.exit(5)
if len(hits) > 1:
    print(f"AMBIGUOUS: {len(hits)} distinct tap targets for {target!r}:", file=sys.stderr)
    for lb, pt in hits:
        print(f"  {lb!r} at {pt}", file=sys.stderr)
    sys.exit(3)

lb, (cx, cy) = hits[0]
if re.search(DESTRUCTIVE, lb, re.I) and not force:
    print(f"REFUSED: {lb!r} looks destructive. Pass --force-destructive if you truly mean it.",
          file=sys.stderr)
    sys.exit(4)

adb = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
subprocess.run([adb, "shell", "input", "tap", str(cx), str(cy)], check=True)
print(f"tapped {lb!r} at device pixels ({cx},{cy})")
PY
