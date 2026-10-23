# Rehearsal Hall (演讲排练厅)

Give your talk on the stage of a full cartoon auditorium; when the lights come up, every seat is tinted by how long you
looked at it. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, package `io.embodit.rehearsalhall`. Skeleton C (immersive +
one panel), container **STAGE_FULL** (default Stage, manifest `pico.spatial.stage.style=3`), window model
`stage_with_lectern_panel`: one lectern panel (760 × 480 dp = 0.46 m) + nine read-only zone signs with the report. No
WindowContainer, no volume, no network. Only optional permission: RECORD_AUDIO.

Design package (binding): `../spatial-app-factory/apps/rehearsal-hall/design-package.json` (states S00 - S15 +
modifiers M1 - M8, gaze model, hall geometry, palette, copy). PRD https://claude.ai/artifact/UMWpgC236KiHGbQhfjom2h,
sample https://claude.ai/artifact/MZN4NYVvz2aE5yD4wWgjjx. Scaffold from Sky Dome (`../sky-dome`: panel pool, Stage root,
head tracker, theme, fonts, debug receiver, emulator harness, itself from Spatial Ruler / Furniture Fit).

## Structure

```
app/src/main/java/io/embodit/rehearsalhall/
  Main.kt                 DefaultStage (Full) -> HallScreen
  domain/                 pure Kotlin, JVM-tested (app/src/test, 8 tests)
    HallLayout.kt         raked hall (9 rows, 168 seats, ±48°), zones (column ±16°, tier edges from eye height),
                          GazeClassifier (lectern < 20°, back > 100°, audience fan, 8° seat cone)
    Rehearsal.kt          GazeRecorder (per-frame dwell + 10 Hz samples), Samples codec, VoiceMath, Session, Report
                          (◯ < 3 % / ◐ / ● ≥ 8 %, verdict rules), SeatTint (0.25/0.5/0.25 smoothing), Deck, Clock
    Geometry.kt Vec3.kt   HeadPose, Yaw (0 = Stage -Z, + = left), FacePanel, vectors / quaternions
  platform/               HallLog (tag RehearsalHall), Store (decks/, sessions/ JSON), SampleDeck (built-in 5 pages,
                          name board), Importer (PdfRenderer / images), Mic (AudioRecord RMS 10 Hz),
                          HelperActivities (PickerActivity, MicPermissionActivity), tracking/ (HeadTracker,
                          ControllerTriggers), LaunchActivity, SpatialApplication
  ui/hall/
    HallUiState.kt        Base S00..S15, events, tips, report view
    HallController.kt     ALL behaviour (state machine, talk clock, import, mic, storage, debug seams)
    HallScreen.kt         wires controller, scene and panels; lifecycle -> pause / background rules
    scene/HallScene.kt    ECS: hall build, placement (1 s wait + 0.5 s median), gaze -> GazeRecorder, drift / nod,
                          listeners (1/6 per frame), lights, screen, beam, panel placement; one registered System
    scene/HallMeshes.kt   toon Palette (lit/shade blocks in one texture), Toon builder, Figures (heads, faces, arms,
                          torso, seats, hall shell, glow, tints, cones)
    scene/SceneKit.kt     HallMaterials (house / stage / glow tints, screen, beam), MeshBuilder, Shapes
    scene/PanelRig.kt     the pre-built panel pool (Spatial Ruler findings)
    components/           HallStage (SpatialView + 10 panels), Panels.kt (all lectern pages + zone sign), Strings
  ui/theme/               HallTheme (PicoTheme + 4 semantic roles + Noto Sans / SC), HallColors, HallTypography
app/src/debug/            DebugCommandReceiver + DebugCommands (QA hooks)
tools/                    dev.sh, qa_tour.sh, cam.py (emulator camera), make_icon.py, subset_cjk_font.py, harness/
```

One direction of data: panel taps / debug hooks -> `HallController.onEvent` -> `HallUiState` -> `HallScene.render` and
the panels. The head samples go from the ECS system straight into `HallController.recorder` (never through Compose
state, design lockedByCritic). Talk time uses the wall clock (pauses / S08 stop it); gaze seconds use frame dt.

## Platform facts found here

- Full Stage with only Unlit materials needs no IBL: the cartoon two-tone shading is baked into palette UVs (each colour
  is a lit block and a shade block side by side; U slides by N·L). House lights = tint of the shared house material.
- `ModelComponent.mesh` is settable: the face expressions are five shared meshes swapped per listener.
- Every `MeshResource.createWithMeshModel` costs about one rendered frame: ~220 meshes, so the scene builds in ~55 s
  on the emulator at 1 - 4 fps (S00 until then); a headset at 72+ fps should take a few seconds. Device check pending.
- On the emulator `HMDTrackingProvider` reports a pose (eye 1.39 m, pitch -13° at launch): N2 = yes, the hall is placed
  from it. The controller provider stays `DEVICE_NOT_SUPPORTED` on PICO_6_1_Factory5 (trigger page turn = headset only).
- AlertDialog / popup menus do not work in a Stage: the deck "⋯" actions open inline under the row.
- Text on a bitmap texture: V runs from the bottom row, every bitmap is flipped once before upload.

## UI rules

- All 2D UI is SpatialUI inside `PicoTheme`. No Material / Material3 (gate: `../spatial-app-factory/bin/material-grep.txt`).
- Copy in `res/values` (en) / `res/values-zh`; read through `rs()` / `LocalHallResources` so the language switches live.
  After adding Chinese characters run `python tools/subset_cjk_font.py`.

## Build, run (Git Bash; device commands pinned to emulator-5568 / AVD PICO_6_1_Factory5)

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh relaunch
tools/dev.sh log 'STATE|PLACE|SESSION|DRIFT' 40
tools/dev.sh crash
tools/dev.sh shot NAME                         # .scratch/shots/NAME.png
./gradlew.bat testDebugUnitTest                # JAVA_HOME = primer JDK 21 (see tools/dev.sh)
python -I tools/make_icon.py ../spatial-app-factory/apps/rehearsal-hall/icon.png
```

## Debug commands (debug builds only)

`adb -s emulator-5568 shell "am broadcast -a io.embodit.rehearsalhall.DEBUG -p io.embodit.rehearsalhall --es cmd NAME --es s 'ARGS'"`
or `tools/dev.sh cmd NAME 'ARGS'`. Each answers `CMD NAME -> result` (tag `RehearsalHall`); each base change logs
`STATE S06+M3 why=...`. Full list at the top of `app/src/debug/.../DebugCommands.kt`:

| command | effect |
|---|---|
| `state S00` … `state S15` | go to a base state (S06/S07/S08 start a talk without the countdown; S09 opens a fake report; S12 makes 3 fake sessions if none) |
| `state name LABEL` / `state clear` | name override for screenshots |
| `setHead YAW PITCH` / `setHead off` / `setZone 0..8` | head direction, hall-local degrees (+ right, + up) / real head / a zone's centre |
| `page next|prev|N` | turn the page (N 1-based) |
| `start` `pause` `resume` `end` `endok` `keep` | T1, T9, T10 |
| `fakeSession SEED [open]` | a synthetic session for the current deck |
| `fakeVoice RATIO PAUSE_S DBFS` | voice numbers of the next report |
| `mic on|off|nosignal` | microphone switch / "no signal" |
| `driftAfter SEC` | shortens the 60 s drift timers (T12) |
| `countdown hold|run` | S05 holds at "2" for screenshots |
| `importPath /sdcard/Download/x.pdf` | import without the picker (T3) |
| `target MIN` `deck sample|none|ID` `tab gaze|time|voice` `replay` `done` `again` `back` | |
| `lang system|zh|en` `drift on|off` `replace` | |
| `firstRun reset` / `killFlag` | M5 again / kill during a talk -> M8 on the next start |
| `dump` `pose` `panels` `audience` | diagnostics |

## Screenshot list (QA)

`tools/qa_tour.sh` (all) or `tools/qa_tour.sh S06 zh` writes `../spatial-app-factory/apps/rehearsal-hall/build/states/*.png`
and `tour.txt` (shot -> logged state). Default deck = sample, English; `zh` shots at the end.

| shot | how |
|---|---|
| S00 | `state S00` (holds the loading page) |
| S01+M5 / S01 | `firstRun reset` / plain |
| S01+M8 | `state S06`, `killFlag`, then `tools/dev.sh launch` (tip "上次排练中断了，没有保存") |
| S02 S03 S04 S11 S12 S13 S14 S15 | `state <id>` (S03 shows 7 / 24, S04 the password case) |
| S05 | `countdown hold`, `start` |
| S06 / S06_page3 / S07 / S08 | `state S06` + `setHead 0 0`; `page 3`; `pause`; `end` |
| S09.gaze / S09.time / S09.voice+M1 / S09.voice | `end`, `endok`, `tab …`; `fakeVoice 0.78 6 -30` + `fakeSession 5 open` |
| S10 | `replay`, 2 s |
| S06+M7 | `deck none`, `state S06` |
| S06+M4 | `driftAfter 5`, `setHead 32 0` (look right), ~12 s: left column middle/back rows on phones |
| S06+M2 / S06+M3 | `target 1`, talk 54 s / 61 s |
| zh-S01 zh-S06 zh-S09 | `lang zh` |

Real imports (scoped storage: the app cannot read Download directly, the picker copies; the debug hook reads the app's own dir): `MSYS_NO_PATHCONV=1 adb -s emulator-5568 push deck.pdf /sdcard/Android/data/io.embodit.rehearsalhall/files/deck.pdf` then `MSYS_NO_PATHCONV=1 tools/dev.sh cmd importPath /sdcard/Android/data/io.embodit.rehearsalhall/files/deck.pdf` (24-page and password-locked test PDFs: `.scratch/pdf/`, verified: 24/24 in < 1 s, locked -> S04).

Emulator notes: panels change 2 - 4 s after a command; the emulator renders at 1 - 4 fps unless its window is focused
(`dev.sh shot` posts a focus-in and keeps the second of two captures), so listeners turn slowly there.
