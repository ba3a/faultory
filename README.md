# faultory

Desktop-only factory-quality scaffold built with Kotlin, LibGDX, JBox2D, and `kotlinx.serialization` for JSON saves.

## Modules

- `core`: game bootstrap, screens, content loaders, shop model, and save pipeline
- `desktop`: LWJGL3 desktop launcher
- `assets`: JSON-authored prototype content

## Current shape

- `FaultoryGame` bootstraps the runtime and creates a starter save slot.
- `BootScreen` loads prototype content and switches into a placeholder shop-floor screen.
- `ShopFloor` wraps a JBox2D-backed simulation shell for future worker and product movement.
- Save files are encoded as JSON and written outside the repo:
  - Windows: `%APPDATA%\Faultory\saves`
  - Fallback: `~/.faultory/saves`

## Run

- `./gradlew :desktop:run`
- `./gradlew build`

## Next architecture decisions

- What happens after a faulty product is detected: reject, rework, or just mark
- How worker routing and assignment should work on the shop floor
- Whether each shop runs one conveyor line or multiple concurrent product lines
