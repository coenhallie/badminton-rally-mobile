#!/usr/bin/env bash
# Proves adb-tap.sh's refusals still fire. No device needed: a stub adb serves a
# canned uiautomator dump and records taps instead of performing them.
#
# This exists because the tool that was written to stop four accidental sign-outs
# later shipped a path that would have caused a fifth. Every guard below is one a
# review found missing, so each case here is a regression test, not a formality.
#
#   tools/adb-tap-test.sh
set -uo pipefail
cd "$(dirname "$0")/.."

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/win.xml" <<'XML'
<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node index="0" text="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false">
    <node index="0" text="Settings" class="android.view.View" bounds="[0,200][1080,2000]" clickable="false">
      <node index="0" text="Sign out" class="android.widget.Button" bounds="[40,1700][1040,1820]" clickable="true"/>
      <node index="1" text="Remove from app" class="android.widget.Button" bounds="[40,1500][1040,1620]" clickable="true"/>
    </node>
    <node index="1" text="Court frame" class="android.view.View" bounds="[0,300][1000,1100]" clickable="true"/>
    <node index="2" text="Switch to dark mode" class="android.widget.Button" bounds="[40,2100][500,2200]" clickable="true"/>
  </node>
</hierarchy>
XML

cat > "$WORK/adb" <<EOF
#!/usr/bin/env bash
if [ "\$2" = "cat" ]; then cat "$WORK/win.xml"; exit 0; fi
if [ "\$2" = "input" ]; then echo "TAP \$4,\$5"; exit 0; fi
exit 0
EOF
chmod +x "$WORK/adb"
export ADB="$WORK/adb"

pass=0; fail=0
# expect WHAT EXIT-CODE OUTPUT-SUBSTRING -- ARGS...
expect() {
  local what="$1" want_code="$2" want_out="$3"; shift 4
  local out code
  out=$(tools/adb-tap.sh "$@" 2>&1); code=$?
  if [ "$code" = "$want_code" ] && [[ "$out" == *"$want_out"* ]]; then
    printf 'ok    %s\n' "$what"; pass=$((pass+1))
  else
    printf 'FAIL  %s\n      wanted exit %s containing %q\n      got    exit %s: %s\n' \
      "$what" "$want_code" "$want_out" "$code" "$out"; fail=$((fail+1))
  fi
}

expect "--within refuses a destructive container" \
  4 "looks destructive" -- --within "Sign out" --at 0.5,0.5
expect "--within refuses a point that lands on a destructive control" \
  4 "is inside 'Sign out'" -- --within "Settings" --at 0.5,0.85
# "Remove" trips the destructive guard before matching is even reached, so it
# cannot show that matching is exact. "Court" can: it is a prefix of the only
# container in the dump, so substring matching finds it and exact matching does
# not. Reverting hosts to the substring form must turn this case red.
expect "--within matches exactly, so a prefix is not silently accepted" \
  2 "NOT FOUND" -- --within "Court" --at 0.5,0.5
expect "--within refuses a destructive container before it even matches" \
  4 "looks destructive" -- --within "Remove" --at 0.5,0.5
expect "--at without --within refuses instead of tapping the centre" \
  6 "needs --within" -- "Court frame" --at 0.25,0.80
expect "--within without --at refuses" \
  6 "needs --at" -- --within "Court frame"
expect "--within with a missing value gives usage, not an unbound-variable crash" \
  6 "needs a value" -- --within
expect "--at with a missing value gives usage" \
  6 "needs a value" -- --at
expect "--at rejects pixels passed by habit" \
  6 "not pixels" -- --within "Court frame" --at 250,940
expect "--at rejects non-numbers" \
  6 "not two numbers" -- --within "Court frame" --at "a,b"
expect "the plain path still refuses a destructive label" \
  4 "looks destructive" -- "Sign out"

expect "a benign label still taps" \
  0 "tapped 'Switch to dark mode'" -- "Switch to dark mode"
expect "a benign fraction taps the fraction, not the centre" \
  0 "TAP 250,940" -- --within "Court frame" --at 0.25,0.80
expect "--force-destructive still works when truly meant" \
  0 "tapped 0.5,0.5 within 'Sign out'" -- --within "Sign out" --at 0.5,0.5 --force-destructive

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
