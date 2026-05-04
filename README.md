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
- `./gradlew :desktop:run`
- `./gradlew build`
- `./gradlew :editor:run`
```
