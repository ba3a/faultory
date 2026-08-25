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
steps again for `idle`. Only when nothing resolves does the entity fall through to the
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

## Adding New Features

1. Add/extend domain model in `core.content`, `core.shop`, `core.systems`, or `core.save`.
2. If asset-backed: add/update JSON in `assets/` and wire path in `AssetPaths`.
3. If loading changes: update the matching `*AssetLoader` (register it in `FaultoryGame.create`).
4. If startup-affected: wire through `FaultoryGame` and `BootScreen`.
5. If rendering/gameplay-affected: update or add a screen in `core.screens`.

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
- Do not add simulation logic directly to `ShopFloor`. New `update*` behaviour belongs in one of the focused system classes (`ConveyorSystem`, `ProductionSystem`, `QaSystem`, `WorkerObjectiveSystem`, `WorkerMovementSystem`, `SecuritySystem`) or in a new system class if the responsibility is genuinely distinct. `ShopFloor` must remain a thin facade.
