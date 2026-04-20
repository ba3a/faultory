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

### MVP carry-overs (game design)
- What happens after a faulty product is detected: reject, rework, or just mark
- How worker routing and assignment should work on the shop floor
- Whether each shop runs one conveyor line or multiple concurrent product lines

### Structural debt to address before scaling
- **Split `ShopFloorScreen` (1 459 lines)** into at least three parts: a renderer, an input handler, and a UI-state coordinator. The current class mixes rendering, input, save scheduling, game orchestration, and all intermediate UI state.
- **Remove JBox2D or commit to it.** `ShopPhysics` wraps a gravity-free Box2D world that does nothing for grid-based tile movement. Either give the physics engine a real role (collision, ragdoll, projectiles) or remove the dependency and the wrapper entirely.
- **Save migration strategy.** `JsonSaveCodec.isCompatibleVersion` does an exact-version check; any bump silently drops the save. Define whether to auto-wipe, prompt the user, or implement a migration chain before `CURRENT_VERSION` stabilises.
- **Reduce auto-save frequency.** Flushing to disk every 0.5 s (120 writes/min) is unnecessary for a desktop game. A 5–10 s interval or save-on-pause/exit is sufficient.
- **Eliminate dual elapsed-time tracking.** `ShopFloor.elapsedSeconds` and `ProductionDayDirector`'s own timer are kept in parallel and can drift. One authoritative clock should drive both.
- **Remove redundant `resolveWorkerObjectives()` calls.** It is called four times inside a single `ShopFloor.update()` tick (after each sub-system). A single call at the end of the tick is correct; the repeated calls mask ordering issues that should be fixed directly.
- **Don't re-parse the level catalog at runtime.** `ShopFloorScreen.nextLevel` is a `by lazy` that calls `levelCatalogLoader.load(...)` — re-reading and re-parsing the entire JSON just to resolve one ID. Pass the resolved `LevelDefinition?` in from `BootScreen` instead.
- **Replace `lateinit var` service bag on `FaultoryGame`.** `spriteBatch`, `uiFont`, and `shapeRenderer` are public fields accessed directly by every screen. A small `RenderContext` value object would make dependencies explicit and ease future testing.
- **Adopt LibGDX `AssetManager`** for content loading so that loading is asynchronous and assets are not re-parsed on every screen transition.
