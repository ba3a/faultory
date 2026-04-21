# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin-first Gradle multi-module LibGDX desktop game. Modules:

- `core` — game runtime, domain models, JSON loaders, save handling, screen flow, simulation systems
- `desktop` — LWJGL3 launcher only (keep thin)
- `assets` — runtime JSON content loaded by LibGDX at startup (not a Gradle module)
- `buildSrc` — shared Gradle convention plugin (`buildsrc.convention.kotlin-jvm`)

No HTTP stack, REST API, database, ORM, web framework, or DTO/entity/mapper packages.

## Build & Test Commands

```
.\gradlew.bat build --console plain        # full build
.\gradlew.bat test --console plain         # run tests (currently NO-SOURCE)
.\gradlew.bat :desktop:run --console plain # run the game (working dir set to assets/)
```

Run `test` after every change. If you touched build wiring, launcher code, dependencies, or startup flow, also run `build`.

## Architecture

Package-by-responsibility under `core/src/main/kotlin/com/faultory/core`:

| Package | Responsibility |
|---|---|
| `core.assets` | Asset path constants (`AssetPaths`) |
| `core.config` | Global runtime constants (`GameConfig`) |
| `core.content` | JSON-backed catalog/content models (`@Serializable data class`) and their `AssetManager` loaders (`*AssetLoader`) |
| `core.shop` | Shop/level layout models (`@Serializable data class`) and `ShopBlueprintAssetLoader` |
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

## Adding New Features

1. Add/extend domain model in `core.content`, `core.shop`, `core.systems`, or `core.save`.
2. If asset-backed: add/update JSON in `assets/` and wire path in `AssetPaths`.
3. If loading changes: update the matching `*AssetLoader` (register it in `FaultoryGame.create`).
4. If startup-affected: wire through `FaultoryGame` and `BootScreen`.
5. If rendering/gameplay-affected: update or add a screen in `core.screens`.

Modifying a `@Serializable` model: update the data class → update matching JSON assets → update the loader if root structure changed → update `GameSave.bootstrap()` if save defaults are affected → update any screen/system reading the changed fields.

## Constraints

- Do not add code to `desktop` beyond launcher concerns.
- Do not revive `core.world` — active domain packages are `core.shop`, `core.content`, `core.systems`, `core.save`.
- Do not rename JSON fields or asset paths casually — they are part of the save/content format.
- Do not introduce new dependencies without strong justification; add via `gradle/libs.versions.toml` and the relevant `build.gradle.kts`.
- Do not introduce Spring, Ktor, Hibernate, or any backend framework speculatively.
- New tests go under `core/src/test/kotlin/com/faultory/core/...`.
