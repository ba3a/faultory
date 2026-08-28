# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin-first Gradle multi-module LibGDX desktop game. Modules:

- `core` — game runtime, domain models, JSON loaders, save handling, screen flow, simulation systems
- `desktop` — LWJGL3 launcher only (keep thin)
- `editor` — standalone LibGDX asset editor (`.\gradlew.bat :editor:run`); owns validation, atlas baking, backup/restore, i18n, and reflection-driven inspector UI. Keep editor logic in `editor`; do not bleed it into `core`.
- `assets` — runtime JSON content loaded by LibGDX at startup (not a Gradle module)
- `buildSrc` — shared Gradle convention plugin (`buildsrc.convention.kotlin-jvm`)

No HTTP stack, REST API, database, ORM, web framework, or DTO/entity/mapper packages.

## Shell

This is Windows 10. Two shells are available and they are **not** interchangeable — pick one per
command and use that shell's syntax.

- **Bash tool = Git Bash.** The default for everything. `cat`, `grep`, `find`, `sed`, `head`, pipes,
  `&&`, heredocs and `/dev/null` all work. Use forward slashes.
- **PowerShell tool = Windows PowerShell 5.1.** No `&&`, no `||`, no `head`/`tail`/`which`. Reach for
  it only when a PowerShell cmdlet is genuinely the right tool.

The wrapper is a `.bat`, so its invocation differs per shell. Getting this wrong is the single most
common failure in this repo:

| Shell | Correct | Wrong |
|---|---|---|
| Bash | `./gradlew.bat test --console plain` | `.\gradlew.bat …` — the backslash is swallowed and you get `.gradlew.bat: command not found` |
| PowerShell | `.\gradlew.bat test --console plain` | chaining with `&&` — PowerShell 5.1 has no pipeline chain operators |

Two more rules that follow from the above:

- Do not redirect a `gradlew.bat` run through `2>&1` in PowerShell. PS 5.1 wraps native stderr in
  ErrorRecords and reports failure on an exit-0 build. Redirect in Bash, or don't redirect.
- Backslashes in a Bash heredoc get collapsed by the escaping layer. When writing a file whose
  content contains literal backslashes (JSON with Windows paths, regex), use the Write tool.

## Build & Test Commands

Shown in Bash form; swap `./` for `.\` in PowerShell.

```bash
./gradlew.bat build --console plain        # full build
./gradlew.bat test --console plain         # run tests
./gradlew.bat :desktop:run --console plain # run the game (working dir set to assets/)
./gradlew.bat :editor:run --console plain  # run the asset editor
./gradlew.bat :core:test --tests "com.faultory.core.shop.ShopFloorQaTest" --console plain
./gradlew.bat detekt --console plain    # static analysis; also runs as part of `check`/`build`
```

A cold Gradle daemon pushes `build` and `:core:test` past the 2-minute default tool timeout — pass
an explicit `timeout` of ~420000 ms rather than retrying.

Run `test` after every change. If you touched build wiring, launcher code, dependencies, or startup flow, also run `build`.

The Gradle daemon is pinned to Java 21 by `gradle/gradle-daemon-jvm.properties`, matching the
`jvmToolchain(21)` the modules compile against. Do not remove it: detekt 1.23.x bundles the Kotlin
1.9 compiler, which dies with `IllegalArgumentException: 25.0.2` on a JDK 25 daemon.

## Architecture

Package-by-responsibility under `core/src/main/kotlin/com/faultory/core`:

| Package | Responsibility |
|---|---|
| `core.assets` | Asset path constants (`AssetPaths`) |
| `core.config` | Global runtime constants and shared JSON config (`GameConfig`, `FaultoryJson`) |
| `core.content` | JSON-backed catalog/content models (`@Serializable data class`) and their `AssetManager` loaders (`*AssetLoader`) |
| `core.shop` | Shop/level layout models (`@Serializable data class`) and `ShopBlueprintAssetLoader` |
| `core.shop.systems` | Per-responsibility simulation systems extracted from `ShopFloor`: `ShopFloorState` (mutable state + shared helpers), `ConveyorSystem`, `ProductionSystem`, `QaSystem`, `WorkerObjectiveSystem`, `WorkerMovementSystem`, `SecuritySystem`. `ShopFloor` is now a thin facade that wires these together. |
| `core.encounters` | The event feed (`GameEvent`, `EventBus`, `ShopFloorEvents`), authored `Condition`/`Encounter` models and `EncounterEngine` |
| `core.screens` | LibGDX screen classes (names end in `Screen`) |
| `core.systems` | Time-step and simulation coordinators (noun names, e.g. `ProductionDayDirector`) |
| `core.save` | Save models, codecs, `SaveRepository` / `LocalSaveRepository` |
| `core.capture` | Capture mode's composition root and supporting types - see **Capture mode** below |

Data flow:

```
DesktopLauncher → FaultoryGame.create()
  → SaveRepository + AssetManager (with ShopCatalog/LevelCatalog/ShopBlueprint loaders) initialized; level + shop catalogs queued
  → BootScreen polls assetManager.update() and renders a progress bar until assets are resident
  → initial boot: transitions to LevelSelectionScreen (which reads via assetManager.get)
  → pre-level boot: retrieves cached catalogs + blueprint, builds ShopFloor + ShopFloorScreen
  → ShopFloorScreen.render() updates ShopFloor + ProductionDayDirector
```

Naming patterns: data models are nouns (`ShopCatalog`, `ShiftSnapshot`); `AssetManager` loaders are named `<Type>AssetLoader`; singleton config holders use `object` (`GameConfig`, `AssetPaths`, `FaultoryJson`).

## Sprite rendering

A skin id resolves to a `SkinDefinition` (`assets/skins/<id>.json`), an `*ActionResolver` maps
entity state to an action name plus an orientation, and `SkinFrameResolver` picks the clip —
degrading through orientations, then stand-ins, then `idle`, and only then falling back to the
`ShapeRenderer` primitives. Action names are constants (`SkinActions`, `ProductActions`,
`BeltActions`), never literals at the call site. Raw art lives in
`raw-art/<skinId>/<action>_<orientation-lowercase>/NNN.png` and is baked from the editor.

**When a resolver learns a new action, add it to `SkinActionCatalog` too** — an action the runtime
asks for but nobody can author is an animation that never plays. `SkinActionCatalogTest` enforces
this.

Full contract — degradation order, sockets vs. interactions, mirroring, layer discipline — is in
the **`skin-actions`** skill (`.claude/skills/skin-actions/`). Load it before touching a resolver,
a renderer, a `ShopFloorLayer`, or anything under `raw-art/`.

## Events

Everything that happens on the factory floor publishes a `GameEvent`, whether or not anything is
listening. The feed is the single source achievements, storytelling beats, in-game encounters and
statistics all read from, so an event missing today is a feature that cannot be built tomorrow
without reopening the simulation.

Systems publish through `ShopFloorEvents`, which stamps the current level on the event. It is never
null and never optional: `events.publish { SomethingHappenedEvent(…, levelId = it) }`.

Continuous motion is deliberately not published — a product advancing one belt tile emits nothing.
State transitions do. `counterName` values and the legacy `shipped.<quality>.<scope>` keys are
persisted in `encounters.json`; treat them like JSON field names.

Full contract — which interface to implement, where and when to publish, and how counters are
derived — is in the **`game-events`** skill (`.claude/skills/game-events/`). Load it whenever you
add or change eventful behaviour.

## Capture mode

`core.capture` runs the game as a filming rig for promo footage: chrome that can be switched off
and back on, a seeded and directable simulation, and offline frame export. It is off unless
`-Dfaultory.capture=<tier>` names one (see `README.md` for the full flag table, keymap and the
ffmpeg command to encode an exported sequence), and of the three declared `CaptureTier` entries
only `DEVELOPER` is implemented — `RECORDING` and `DIRECTED` are declared for a future iteration and
fall back to `OFF` today.

**A run is *tainted* when its tier lets anyone influence the simulation** (`DIRECTED`, `DEVELOPER`;
see `CaptureTier.isTainted`). A tainted run must never write to the player's saves, encounter
progress or preferences — that is the one invariant capture mode exists to keep, and
`CaptureIsolationTest` locks it. All three persistence repositories are constructed in
`FaultoryGame.create()` against a root chosen by `persistenceRootFor` (in `core.capture`); never
call `SavePathResolver.defaultRootDirectory()` from anywhere else, and never build a
`LocalSaveRepository`/`LocalEncounterProgressRepository`/`LocalPlayerPreferencesRepository` outside
that one call site.

**Check every new feature against capture mode — some will need tweaking to work under it:**

- **Rolls a random?** Route it through `ChanceOracle` with a `ChanceKind` (`core.shop.systems`), not
  `random.nextFloat()` at the call site, or it can be neither cued nor made repeatable.
- **Draws interface?** Give it a `ChromeElement` (`core.screens.shopfloor`) and gate it with
  `GatedLayer` or an injected `ChromeVisibility`, or it will show up in clean footage.
- **Persists anything?** It must go through a repository built in `FaultoryGame.create()`, or a
  capture run leaks into real progress.
- **Reads wall-clock time or the real frame delta directly?** Capture mode runs a fixed timestep
  (`CaptureRuntime.fixedDeltaSeconds`); anything bypassing it breaks repeatability.
- **Adds a `CaptureTier`?** `CaptureIsolationTest` is exhaustive over `CaptureTier.entries` and will
  fail until you state whether the new tier is tainted.

## Adding New Features

1. Add/extend domain model in `core.content`, `core.shop`, `core.systems`, or `core.save`.
2. If asset-backed: add/update JSON in `assets/` and wire path in `AssetPaths`.
3. If loading changes: update the matching `*AssetLoader` (register it in `FaultoryGame.create`).
4. If startup-affected: wire through `FaultoryGame` and `BootScreen`.
5. If rendering/gameplay-affected: update or add a screen in `core.screens`.
6. If it is eventful: add a `GameEvent` and publish it — see **Events** above.

Modifying a `@Serializable` model: update the data class → update matching JSON assets → update the loader if root structure changed → update `GameSave.bootstrap()` if save defaults are affected → update any screen/system reading the changed fields.

## Code quality

- **Match the existing pattern first.** Before writing a new loader, resolver, renderer, or system, search for one that does the same job. If one exists, follow its exact structure and naming. Introducing a second idiom for the same problem makes the codebase harder to read and extend.
- **Abstract duplicated logic.** When two or more classes share near-identical method bodies, extract a parameterised base class or helper and have each concrete class extend or delegate. The `JsonAsynchronousAssetLoader<T>` base class is the canonical example: four loaders that each decoded JSON were collapsed into one base class and four two-line subclasses.
- **detekt is the floor, not the ceiling.** `config/detekt/detekt.yml` holds the shared rule set on
  top of detekt's defaults; `config/detekt/baseline-<module>.xml` absorbs the findings that predate
  it. New code has to come in clean. Fix a finding rather than widening the rule, and only ever
  regenerate a baseline (`./gradlew.bat detektBaseline`) when you have deliberately accepted a new
  class of finding — never to silence one you introduced.
- **SOLID — weighted for this codebase.** Apply Single Responsibility (each class has one reason to change), Interface Segregation (prefer narrow interfaces over fat ones), and Dependency Inversion (depend on abstractions passed in, not on concrete singletons constructed inside a class). Open/Closed and Liskov follow naturally. When a refactor conflicts with these, prefer S then I then D.

## Constraints

- Do not add code to `desktop` beyond launcher concerns.
- Do not revive `core.world` — active domain packages are `core.shop`, `core.content`, `core.systems`, `core.save`.
- Do not rename JSON fields or asset paths casually — they are part of the save/content format.
- Do not introduce new dependencies without strong justification; add via `gradle/libs.versions.toml` and the relevant `build.gradle.kts`.
- Do not introduce Spring, Ktor, Hibernate, or any backend framework speculatively.
- Tests live in the module they exercise: `core/src/test/kotlin/com/faultory/core/...` for runtime
  and simulation, `editor/src/test/kotlin/com/faultory/editor/...` for validation, baking, backup and
  repository behaviour. `desktop` is launcher-only and has no tests.
- Do not add eventful behaviour without publishing an event for it, and do not reintroduce a
  nullable `EventBus` or a `levelIdProvider` into a system — take `ShopFloorEvents`.
- Do not add simulation logic directly to `ShopFloor`. New `update*` behaviour belongs in one of the focused system classes (`ConveyorSystem`, `ProductionSystem`, `QaSystem`, `WorkerObjectiveSystem`, `WorkerMovementSystem`, `SecuritySystem`) or in a new system class if the responsibility is genuinely distinct. `ShopFloor` must remain a thin facade.
