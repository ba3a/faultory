# faultory

Desktop-only tower-defence scaffold built with Kotlin, LibGDX, JBox2D, and `kotlinx.serialization` for JSON saves.

## Modules

- `core`: game bootstrap, screens, content loaders, world model, and save pipeline
- `desktop`: LWJGL3 desktop launcher
- `assets`: JSON-authored prototype content

## Current shape

- `FaultoryGame` bootstraps the runtime and creates a starter save slot.
- `BootScreen` loads prototype content and switches into a placeholder command-center screen.
- `TowerDefenseWorld` wraps a JBox2D-backed simulation shell for future gameplay systems.
- Save files are encoded as JSON and written outside the repo:
  - Windows: `%APPDATA%\Faultory\saves`
  - Fallback: `~/.faultory/saves`

## Run

- `./gradlew :desktop:run`
- `./gradlew build`

## Next architecture decisions

- Lane/path-only defence or freeform tile placement
- Wave definition format and enemy composition model
- How much gameplay logic should live in data versus Kotlin systems
