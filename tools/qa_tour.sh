#!/usr/bin/env bash
# Rehearsal Hall: screenshot tour of the design states (design states[].qaCapture) on emulator-5568.
#
#   tools/qa_tour.sh            all shots
#   tools/qa_tour.sh S06 S09    only shots whose name starts with one of these
#
# Output: ../spatial-app-factory/apps/rehearsal-hall/build/states/<name>.png and tour.txt (shot -> logged design state).
# The app must be running (tools/dev.sh relaunch). Panels change 2 - 4 s after a command; the emulator renders slowly
# unless its window is focused (dev.sh shot posts a focus-in and keeps the second of two captures).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D="$ROOT/tools/dev.sh"
OUT="${OUT:-$ROOT/../spatial-app-factory/apps/rehearsal-hall/build/states}"
mkdir -p "$OUT"
FILTER=("$@")
TOUR="$OUT/tour.txt"
[ ${#FILTER[@]} -eq 0 ] && : > "$TOUR"

want() {
  [ ${#FILTER[@]} -eq 0 ] && return 0
  for f in "${FILTER[@]}"; do case "$1" in "$f"*) return 0 ;; esac; done
  return 1
}
cmd() { "$D" cmd "$1" "${2:-}" >/dev/null; sleep "${3:-0.6}"; }
shot() {
  local name="$1" wait="${2:-3}"
  sleep "$wait"
  "$D" cmd dump >/dev/null; sleep 0.8
  local st; st="$("$D" log 'CMD dump' 1 | sed -E 's/.*state=([^ ]+).*/\1/')"
  SHOTS="$OUT" SHOT_WAIT=1.5 "$D" shot "$name" >/dev/null
  echo "$name -> $st" | tee -a "$TOUR"
}
reset() {
  cmd setHead off
  cmd driftAfter 60
  cmd lang en
  cmd deck sample
  cmd target 3
  cmd mic off
  cmd state S01 1.5
}

reset
if want S00; then cmd state S00; shot S00 3; cmd state S01 2; fi
if want S01; then cmd firstRun reset; shot S01+M5 3; cmd state S01; shot S01 9; fi
if want S02; then cmd state S02; shot S02 3; fi
if want S03; then cmd state S03; shot S03 3; fi
if want S04; then cmd state S04; shot S04 3; fi
if want S11; then cmd state S11; shot S11 3; fi
if want S12; then cmd state S12; shot S12 4; fi
if want S13; then cmd state S13; shot S13 3; fi
if want S14; then cmd state S14; shot S14 3; fi
if want S15; then cmd state S15; shot S15 3; fi
if want S05; then cmd state S01 1; cmd countdown hold; cmd start; shot S05 2; cmd countdown run; sleep 4; cmd state S01 2; fi
if want S06; then
  cmd setHead "0 0"
  cmd state S06; shot S06 8
  cmd page 3; shot S06_page3 3
  cmd pause; shot S07 3
  cmd end; shot S08 3
  cmd keep 1
  cmd end 1; cmd endok; shot S09.gaze 5
  cmd tab time; shot S09.time 3
  cmd tab voice; shot S09.voice+M1 3
  cmd tab gaze; cmd replay; shot S10 2
  cmd done 2
fi
if want S09; then
  cmd fakeVoice "0.78 6 -30"
  cmd fakeSession "5 open" 2; cmd tab voice; shot S09.voice 4
  cmd tab gaze 1
  cmd state S12 2; cmd back 1
  # history -> read-only report (M6)
  cmd state S12 3
  "$D" cmd dump >/dev/null
fi
if want S06+M7; then cmd deck none 1; cmd state S06; shot S06+M7 6; cmd end 1; cmd endok 2; cmd state S01 1; cmd deck sample 1; fi
if want S06+M4; then
  cmd driftAfter 5; cmd setHead "32 0"
  cmd state S06; shot S06+M4 14
  cmd setHead "-32 0"; shot S06_drift_end 8
  cmd end 1; cmd endok 2; cmd state S01 1; cmd driftAfter 60
fi
if want S06+M2; then
  cmd target 1; cmd setHead "0 0"; cmd state S06
  sleep 50; shot S06+M2 4
  sleep 6; shot S06+M3 2
  cmd end 1; cmd endok 2; cmd state S01 1; cmd target 3
fi
if want zh; then
  cmd lang zh 2; cmd state S01; shot zh-S01 4
  cmd setHead "0 0"; cmd state S06; shot zh-S06 6; sleep 8
  cmd end 1; cmd endok; shot zh-S09 5
  cmd done 1; cmd lang en 1
fi
reset
echo "tour done -> $OUT"
