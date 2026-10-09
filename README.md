# faultory

Desktop-only factory-quality scaffold built with Kotlin, LibGDX, and `kotlinx.serialization` for JSON saves.

## Modules

- `core`: game bootstrap, screens, content loaders, shop model, simulation systems, and save pipeline
- `desktop`: LWJGL3 desktop launcher
- `editor`: standalone LibGDX asset editor — validation, atlas baking, backup/restore, i18n, reflection-driven inspector
- `assets`: JSON-authored prototype content

## Current shape

- `FaultoryGame` bootstraps the runtime and creates a starter save slot.
- `BootScreen` drives a LibGDX `AssetManager` to load prototype content asynchronously, rendering a progress bar until assets are resident, then switches into the level selection or shop-floor screen.
- `ShopFloor` is a thin facade over six focused simulation systems (`ConveyorSystem`, `ProductionSystem`, `QaSystem`, `WorkerObjectiveSystem`, `WorkerMovementSystem`, `SecuritySystem`) that live in `core.shop.systems`. Shared mutable state is held by `ShopFloorState`.
- Save files are encoded as JSON and written outside the repo:
  - Windows: `%APPDATA%\Faultory\saves`
  - Fallback: `~/.faultory/saves`

## Run

```
.\gradlew.bat :desktop:run --console plain   # run the game
.\gradlew.bat :editor:run --console plain    # run the asset editor
.\gradlew.bat build --console plain          # full build
.\gradlew.bat test --console plain           # run tests
 
```
`./gradlew :desktop:run`
`./gradlew build`
`./gradlew :editor:run`

## Shop-floor camera

The floor sits under a pan-and-zoom camera; the HUD, bank panel, menus and modals stay fixed on top.

| Input | Action |
|---|---|
| mouse wheel | zoom in / out in steps — ½×, 1×, 2×, 3× — toward the cursor |
| left-drag on empty floor, a worker or a belt | grab the floor and pan; let go mid-motion to fling it |
| `W` `A` `S` `D` / arrow keys | pan |

Each shop's blueprint frames its camera with two tile rectangles: `camera.rest`, where the view
comes to rest, and `camera.limit`, the furthest it may ever show. Drag past the rest edge and the
view follows on a rubber band, resisting more the further it goes; let go, or fling it over the edge,
and a spring returns it. Keys stop at the rest edge. The shipped shops rest on their floor, so at 1×
the floor exactly fills the play area and the view cannot settle anywhere else; ½× shows the shop's
surroundings. The editor rejects a limit too small to hold the ½× view at rest.

A left press that already means something — a machine (rotate-drag), a bank card, a context-menu option, a
pending assignment target, any tile while a bank item is selected — keeps its meaning and never pans.

## Capture mode

A flag-gated filming rig for promo footage: chrome that can be switched off and back on, a seeded
and directable simulation, and offline frame export. Off unless `-Dfaultory.capture` names a tier;
`./gradlew.bat :desktop:run` forwards every `-Dfaultory.*` property through to the game. Of the
three tiers below, only `DEVELOPER` is implemented today — `RECORDING` and `DIRECTED` are declared
for a later iteration and behave exactly like `OFF` in the meantime.

| Tier | What it grants | Touches your real saves? |
|---|---|---|
| `OFF` (default) | nothing — the shipping game | — |
| `RECORDING` *(not yet implemented)* | a background screencast; gameplay, randomness and UI all behave normally | no |
| `DIRECTED` *(not yet implemented)* | seed the random, cue outcomes, hide chrome, as a player | no |
| `DEVELOPER` | everything below | no |

```bash
.\gradlew.bat :desktop:run --console plain -Dfaultory.capture=DEVELOPER -Dfaultory.capture.level=tutorial-shop -Dfaultory.capture.seed=42
```

| Property | Meaning | Default |
|---|---|---|
| `faultory.capture` | tier name, or `true` as shorthand for `DEVELOPER` | `OFF` |
| `faultory.capture.level` | level id to open straight into, skipping level selection | none |
| `faultory.capture.seed` | simulation seed | `0` |
| `faultory.capture.preset` | starting chrome preset — `NORMAL`, `CLEAN`, `TECHNICAL` | `NORMAL` |
| `faultory.capture.timeline` | absolute path to an authored shot script (JSON) | none |
| `faultory.capture.export` | start frame export immediately on launch | `false` |
| `faultory.capture.outDir` | frame output directory | `<capture root>/frames` |
| `faultory.capture.fps` | fixed timestep / export frame rate | `60` |
| `faultory.capture.borderless` | undecorated, fixed-size window | `true` once any tier is active |
| `faultory.capture.saveRoot` | override for where a tainted run's saves live | `%APPDATA%\Faultory-capture` |

A `DEVELOPER` run never touches `%APPDATA%\Faultory\saves` — it reads and writes under the capture
root instead, starting from an empty profile. If a level needs upstream progress (an unlocked
level, a belt fed by a completed run), copy the relevant save files from `%APPDATA%\Faultory\saves`
into the capture root's `saves\` folder before recording; there is no built-in bridge between the
two, on purpose.

**Hotkeys**, active only under `DEVELOPER`:

| Key | Action |
|---|---|
| `F1` | cycle chrome preset — `NORMAL` → `CLEAN` → `TECHNICAL` |
| `F2` / `F3` / `F4` | toggle the debug overlay / grid lines / HUD + bank panel |
| `F10` | start / stop frame export |
| `1`–`6` | cue the *next* roll of that chance to fire (sabotage, defect, QA detect, QA false-positive, worker slip, cleaner spawn) |
| `Shift`+`1`–`6` | cue the next roll of that chance *not* to fire |
| `Ctrl`+`1`–`6` | standing force — every roll of that kind fires until cleared |
| `Ctrl`+`0` | clear every queued cue and standing force |

`F9` (force shape rendering) and `Esc` keep their normal meaning.

After exporting (`F10` or `-Dfaultory.capture.export=true`), PNGs land in the out directory as
`frame_000000.png`, `frame_000001.png`, … Encode them with:

```bash
ffmpeg -framerate 60 -i frame_%06d.png -c:v libx264 -crf 16 -pix_fmt yuv420p promo.mp4
```

The window is native 1600×900 so LibGDX's `FitViewport` scales it exactly 1:1 — no resampling of
the Nearest-filtered pixel art. Upscale in your video editor rather than asking the window for a
larger size. A shift never ends mid-take if its blueprint's `shiftLengthSeconds` is set well beyond
how long you intend to film.

**Camera moves.** The status line reads out the current framing as `camera x=… y=… zoom=…`: the
world point at the centre of the play area and the zoom step. Frame a shot by hand, copy
those numbers into a timeline `camera` cue, and the move replays frame-exactly on every take
(`seconds` is the ease duration; omit it or use `0` to cut). A cue's `zoom` must be one of the
ladder's steps (`0.5`, `1`, `2`, `3`) — anything else fails the timeline at load — and a cue never
overscrolls: its target is pulled inside the rest extent. A hand-driven move is not repeatable —
script it once it looks right.

```json
{"cues": [
  {"atSeconds": 2.0, "action": {"type": "camera", "x": 640.0, "y": 420.0, "zoom": 2, "seconds": 3.0}},
  {"atSeconds": 9.0, "action": {"type": "camera", "x": 800.0, "y": 480.0, "zoom": 0.5, "seconds": 2.0}}
]}
```
