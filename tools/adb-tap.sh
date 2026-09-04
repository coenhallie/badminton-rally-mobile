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
# Refuses to tap anything whose label matches a destructive pattern unless you
# pass --force-destructive, which you should not.
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
MATCH="exact"; FORCE=0; TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --contains) MATCH="contains"; shift ;;
    --force-destructive) FORCE=1; shift ;;
    --list) MATCH="list"; shift ;;
    *) TARGET="$1"; shift ;;
  esac
done

DUMP=$(mktemp); trap 'rm -f "$DUMP"' EXIT
"$ADB" shell uiautomator dump /sdcard/win.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/win.xml > "$DUMP" 2>/dev/null

python3 - "$DUMP" "$TARGET" "$MATCH" "$FORCE" <<'PY'
import sys, subprocess, os, re
import xml.etree.ElementTree as ET

dump, target, mode, force = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1"
root = ET.parse(dump).getroot()

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
DESTRUCTIVE = r"sign\s*out|log\s*out|delete|remove|wipe|clear|erase|trash|discard|leave|unshare|revoke|reset"
if re.search(DESTRUCTIVE, lb, re.I) and not force:
    print(f"REFUSED: {lb!r} looks destructive. Pass --force-destructive if you truly mean it.",
          file=sys.stderr)
    sys.exit(4)

adb = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
subprocess.run([adb, "shell", "input", "tap", str(cx), str(cy)], check=True)
print(f"tapped {lb!r} at device pixels ({cx},{cy})")
PY
