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
import re, sys, subprocess, os
dump, target, mode, force = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1"
xml = open(dump, encoding="utf-8", errors="replace").read()
nodes = []
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    def attr(n):
        g = re.search(rf'{n}="([^"]*)"', tag)
        return g.group(1) if g else ""
    label = attr("text") or attr("content-desc")
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if label and b:
        x1, y1, x2, y2 = map(int, b.groups())
        nodes.append((label, (x1 + x2) // 2, (y1 + y2) // 2, attr("clickable") == "true"))

if mode == "list":
    for label, cx, cy, click in nodes:
        print(f"{'TAP' if click else '   '}  {label!r}  centre=({cx},{cy})")
    sys.exit(0)

hits = [n for n in nodes if (n[0] == target if mode == "exact" else target.lower() in n[0].lower())]
if not hits:
    print(f"NOT FOUND: {target!r}. Run with --list to see available labels.", file=sys.stderr)
    sys.exit(2)
if len(hits) > 1:
    print(f"AMBIGUOUS: {len(hits)} matches for {target!r}:", file=sys.stderr)
    for h in hits: print(f"  {h[0]!r} at ({h[1]},{h[2]})", file=sys.stderr)
    sys.exit(3)

label, cx, cy, _ = hits[0]
DESTRUCTIVE = r"sign\s*out|log\s*out|delete|remove|wipe|clear|erase"
if re.search(DESTRUCTIVE, label, re.I) and not force:
    print(f"REFUSED: {label!r} looks destructive. Pass --force-destructive if you truly mean it.", file=sys.stderr)
    sys.exit(4)

adb = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
subprocess.run([adb, "shell", "input", "tap", str(cx), str(cy)], check=True)
print(f"tapped {label!r} at device pixels ({cx},{cy})")
PY
