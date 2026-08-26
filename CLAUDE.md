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

## Build & Test Commands

```
.\gradlew.bat build --console plain        # full build
.\gradlew.bat test --console plain         # run tests
.\gradlew.bat :desktop:run --console plain # run the game (working dir set to assets/)
.\gradlew.bat :editor:run --console plain  # run the asset editor
```

Run `test` after every change. If you touched build wiring, launcher code, dependencies, or startup flow, also run `build`.

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

Workers, machines, products and belt tiles are all drawn the same way: a skin id resolves to a
`SkinDefinition` (`assets/skins/<id>.json`), an `*ActionResolver` maps entity state to an action
name and an orientation, and `SkinFrameResolver` picks the clip to draw.

`SkinFrameResolver` degrades in a fixed order, so partially authored art still renders: requested
action facing the requested way, then facing `SOUTH`, then the nearest authored orientation by
turning order (clockwise neighbour, counter-clockwise neighbour, opposite); then those same three
steps again for any stand-in the action declares, and finally for `idle`. Stand-ins exist because
idle is the wrong substitute for anything that plays while the entity is moving or off its feet —
an unauthored `pursue` borrows `walk` rather than freezing a guard mid-stride. Only when nothing resolves does the entity fall through to the
`ShapeRenderer` primitives in `PlacedObjectRenderer` / `GridBackgroundRenderer`. Set
`DebugFlags.forceShapeRendering` (F9 on the shop floor, or `-Dfaultory.debug.shapes=true`) to force
that fallback everywhere.

Action names are constants, never string literals at the call site: `SkinActions` (workers and
machines), `ProductActions`, `BeltActions`. Products additionally support `fault_defect` /
`fault_sabotage` overlay masks drawn over the base frame; without mask art the base sprite is
tinted instead. Belt tiles have no catalog entry — `BeltTopology` derives flow direction and tile
shape from `ShopGrid.orderedBeltPaths`, and the skin is `ConveyorBelt.skin` or
`AssetPaths.defaultBeltSkin`.

Sprite layers resolve what they will draw in `ShopFloorLayer.prepare`, which runs before every draw
pass; that is what lets the shape layers suppress themselves for sprite-backed entities in the same
frame. Do not resolve sprites in `drawSprite`.

Raw art lives in `raw-art/<skinId>/<action>_<orientation-lowercase>/NNN.png` and is baked with the
editor's Tools -> "Bake atlas..." dialog, which works for any skin id.

Right-clicking a cell of the editor's animation grid offers **"Mirror into <ORIENTATION>"**, which
writes a left-to-right flip of that cell's frames, cutout layers and sockets into another
orientation as ordinary raw art and re-bakes. It is a corner-cutting tool for poses whose two
facings differ by nothing but the flip - authoring a distinct animation per orientation is still the
norm - and it deliberately leaves no trace: the copy is indistinguishable from drawn art, and
redrawing the source does not update it. Nothing in `core` knows about it; the flip happens once, at
authoring time, never while drawing.

`SkinActionCatalog` lists the actions each kind can request, and is what the editor turns into
animation grid rows (`AnimationTargets` maps a selected asset to its grids; belts hang off the
blueprint selection, and `SkinActionCatalog.workerActions` merges in both halves of every
interaction in `content/interactions.json`). **When a resolver learns a new action, add it there
too** — an action the runtime asks for but nobody can author is an animation that never plays.
`SkinActionCatalogTest` enforces this by driving the resolvers over every `UnitPhase`,
`BeltRidePhase`, `InteractionRole`, `ShopProductState` and `BeltTileShape`.

Two things that look like actions deliberately are not: **carrying** is the payload riding the
`hands` socket over the ordinary pose, and **handing over** is an interaction, whose two halves are
authored per interaction rather than as constants. Reach for a socket or an interaction before
adding an action.

## Events

Everything that happens on the factory floor publishes a `GameEvent`, whether or not anything is
listening. The feed is the single source achievements, storytelling beats, in-game encounters and
statistics all read from, so an event missing today is a feature that cannot be built tomorrow
without reopening the simulation.

Systems publish through `ShopFloorEvents`, which stamps the current level on the event and hands it
to the `EventBus`. It is never null and never optional: `events.publish { SomethingHappenedEvent(…,
levelId = it) }`. Its default instance publishes into a bus nobody listens to, which is what tests
and headless simulation get for free.

`EncounterEngine` knows nothing about individual event types. Each event names its own counters via
`counterName` and `counterKeys(scope)`; the default gives an all-levels and a per-level total, and
an override adds breakdown keys in the `<counterName>.<scope>.<suffix>` shape that
`Condition.CounterAtLeast` reads. A new event therefore starts accumulating statistics and becomes
usable by authored content the moment it is published.

**When you add eventful behaviour, add the event that publishes it.** Concretely:

1. Add the event to `core.encounters.GameEvent`, implementing `GameEvent` plus whichever of
   `ProductEvent` / `ActorEvent` / `MachineEvent` / `EconomyEvent` describe it, with a
   `counterName` and a `levelId`.
2. Publish it from the system that owns the behaviour, at the moment the state actually changes —
   not where the change was requested. A handover is reported when the payload moves, not when the
   interaction is asked for; a verdict is reported on the frame it is reached, not in a disposition
   step that retries.
3. Publish exactly once per occurrence. Where two actors tick the same event (interactions), only
   the initiator reports; where a step is retried until it succeeds, publish on the transition.
4. Prefer generalising over adding an actor-specific twin — `ProductPickedUpEvent` carries the
   picker's `workerRole` rather than there being one event per role.

`counterName` values and the legacy `shipped.<quality>.<scope>` keys are persisted in
`encounters.json`; treat them like JSON field names and do not rename them casually.

Continuous motion is deliberately not published: a product advancing one belt tile or a worker
crossing one tile emits nothing. State transitions do — entering and leaving a belt, being blocked,
falling, standing up, changing hands, being shipped.

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
- **SOLID — weighted for this codebase.** Apply Single Responsibility (each class has one reason to change), Interface Segregation (prefer narrow interfaces over fat ones), and Dependency Inversion (depend on abstractions passed in, not on concrete singletons constructed inside a class). Open/Closed and Liskov follow naturally. When a refactor conflicts with these, prefer S then I then D.

## Constraints

- Do not add code to `desktop` beyond launcher concerns.
- Do not revive `core.world` — active domain packages are `core.shop`, `core.content`, `core.systems`, `core.save`.
- Do not rename JSON fields or asset paths casually — they are part of the save/content format.
- Do not introduce new dependencies without strong justification; add via `gradle/libs.versions.toml` and the relevant `build.gradle.kts`.
- Do not introduce Spring, Ktor, Hibernate, or any backend framework speculatively.
- New tests go under `core/src/test/kotlin/com/faultory/core/...`.
- Do not add eventful behaviour without publishing an event for it, and do not reintroduce a
  nullable `EventBus` or a `levelIdProvider` into a system — take `ShopFloorEvents`.
- Do not add simulation logic directly to `ShopFloor`. New `update*` behaviour belongs in one of the focused system classes (`ConveyorSystem`, `ProductionSystem`, `QaSystem`, `WorkerObjectiveSystem`, `WorkerMovementSystem`, `SecuritySystem`) or in a new system class if the responsibility is genuinely distinct. `ShopFloor` must remain a thin facade.
