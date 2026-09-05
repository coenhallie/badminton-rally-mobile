#!/usr/bin/env bash
# Every Material container must set its own colour.
#
# Unset, ModalBottomSheet and ModalNavigationDrawer resolve M3's own
# surfaceContainerLow, a purple-tinted platform colour ShuttlColors.kt never
# sets. That shipped once already.
#
# A grep cannot answer this: containerColor sits an unpredictable number of
# lines below the constructor, behind the comment explaining why it is there.
# The line-based version reported all seven correct sheets as violations, and a
# check that cries wolf on correct code gets ignored. This one matches
# parentheses, so it reads the actual argument list.
#
#   tools/check-container-colors.sh
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - "$@" <<'PY'
import re, sys, pathlib

WANTED = {"ModalBottomSheet": "containerColor", "ModalNavigationDrawer": "drawerContainerColor"}
bad = []
for path in pathlib.Path("androidApp/src/main").rglob("*.kt"):
    src = path.read_text()
    for name, arg in WANTED.items():
        for m in re.finditer(rf"\b{name}\s*\(", src):
            i, depth = m.end(), 1
            while i < len(src) and depth:
                depth += (src[i] == "(") - (src[i] == ")")
                i += 1
            args = src[m.end():i - 1]
            if arg not in args:
                line = src[:m.start()].count("\n") + 1
                bad.append(f"{path}:{line}  {name} sets no {arg}")

if bad:
    print("Material containers with no colour of their own:", file=sys.stderr)
    for b in bad:
        print(f"  {b}", file=sys.stderr)
    sys.exit(1)
print("every Material container sets its own colour")
PY
