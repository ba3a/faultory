# faultory

Desktop-only factory-quality scaffold built with Kotlin, LibGDX, and `kotlinx.serialization` for JSON saves.

## Modules

- `core`: game bootstrap, screens, content loaders, shop model, and save pipeline
- `desktop`: LWJGL3 desktop launcher
- `assets`: JSON-authored prototype content

## Current shape

- `FaultoryGame` bootstraps the runtime and creates a starter save slot.
- `BootScreen` drives a LibGDX `AssetManager` to load prototype content asynchronously, rendering a progress bar until assets are resident, then switches into the level selection or shop-floor screen.
- `ShopFloor` coordinates grid-based worker and product movement on the shop floor.
- Save files are encoded as JSON and written outside the repo:
  - Windows: `%APPDATA%\Faultory\saves`
  - Fallback: `~/.faultory/saves`

## Run

- `./gradlew :desktop:run`
- `./gradlew build`

### Structural debt to address before scaling
- **Save migration strategy.** `JsonSaveCodec.isCompatibleVersion` does an exact-version check; any bump silently drops the save. Define whether to auto-wipe, prompt the user, or implement a migration chain before `CURRENT_VERSION` stabilises.
- **Pass the resolved `LevelDefinition?` through `ShopFloorScreen`.** `ShiftLifecycleController.nextLevel` now hits the cached `AssetManager` catalog (O(1)), but threading the resolved value in from `BootScreen` would remove the lookup entirely.
