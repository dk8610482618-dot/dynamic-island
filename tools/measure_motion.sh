#!/usr/bin/env bash
# Measures the island's expansion frame cost on a running emulator.
#
# The island is a near-black rounded rect drawn over whatever is behind it, so
# it is located by scanning a screenshot for a wide run of near-black pixels
# bounded by bright ones on both sides. Pressing the centre of that run and
# holding is a long-press, which is the real gesture that expands the island.
#
# Usage: tools/measure_motion.sh [label]
set -u
export MSYS_NO_PATHCONV=1
A="${ADB:-/d/Android/sdk/platform-tools/adb.exe}"
PKG=com.dynamicisland.host
LABEL="${1:-measurement}"
SHOT="D:/dyn island/screenshots/baseline/_locate.png"

"$A" shell screencap -p /sdcard/_locate.png >/dev/null 2>&1
"$A" pull /sdcard/_locate.png "$SHOT" >/dev/null 2>&1

read -r CX CY <<<"$(python -c "
from PIL import Image
im = Image.open(r'$SHOT').convert('RGB')
w,h = im.size; px = im.load()
cands=[]
for y in range(80, 900):
    xs=[x for x in range(w) if sum(px[x,y])<40]
    if len(xs)<80: continue
    lo,hi=xs[0],xs[-1]
    if len(xs) < (hi-lo)*0.8: continue
    if sum(px[max(0,lo-25),y])>240 and sum(px[min(w-1,hi+25),y])>240:
        cands.append((y,lo,hi))
if not cands:
    print('0 0'); raise SystemExit
ys=[c[0] for c in cands]
lo=min(c[1] for c in cands); hi=max(c[2] for c in cands)
print((lo+hi)//2, (min(ys)+max(ys))//2)
")"

if [ "$CX" = "0" ]; then echo "$LABEL: island not found on screen"; exit 1; fi
echo "$LABEL: island at ($CX,$CY)"

"$A" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
# Three long-presses: expand, collapse, expand — so both transitions are sampled.
for _ in 1 2 3; do
    "$A" shell input swipe "$CX" "$CY" "$CX" "$CY" 700 >/dev/null 2>&1
    sleep 1.2
done
sleep 2

"$A" shell dumpsys gfxinfo "$PKG" 2>/dev/null |
    grep -E "Total frames|^Janky frames:|50th percentile|90th percentile|Slow issue|Missed Vsync"
