# AGENTS.md

## Project Overview
This repository is a Kotlin-first Gradle multi-module desktop game scaffold, not a Spring/MVC backend. The build includes only `:core` and `:desktop` as declared in `settings.gradle.kts`.

- `core` contains the game runtime, domain models, JSON loaders, save handling, screen flow, and simulation systems.
- `desktop` contains the LWJGL3 launcher only.
- `assets` contains runtime JSON content loaded by LibGDX at startup. It is not a Gradle module.
- `buildSrc` contains the shared Gradle convention plugin (`buildsrc.convention.kotlin-jvm`) used by the modules.

Current architecture is package-by-responsibility, not controller/service/repository layering:

- Bootstrap: `FaultoryGame`, `DesktopLauncher`
- Presentation: `core.screens`
- Domain/config/content: `core.shop`, `core.content`, `core.systems`, `core.config`, `core.assets`
- Persistence: `core.save`

Current data flow:

- `desktop` starts `DesktopLauncher`.
- `DesktopLauncher` creates `FaultoryGame`.
- `FaultoryGame.create()` initializes the renderer, save repository, and JSON loaders.
- `BootScreen` loads JSON assets from `assets/` and the bootstrap save, then creates `ShopFloor` and `ShopFloorScreen`.
- `ShopFloorScreen.render()` updates `ShopFloor` and `ProductionDayDirector`, then renders placeholder visuals with LibGDX `ShapeRenderer`.

Important repository facts:

- The codebase is Kotlin, not Java. New production code should follow the existing Kotlin style unless the task explicitly requires Java.
- There is no HTTP stack, no REST API, no database, no ORM, and no web framework.
- There are no DTO/entity/mapper packages. JSON-backed domain models are plain `@Serializable` data classes in their owning package.

## Build & Test Commands
Use the Gradle wrapper from the repository root.

- Build the whole project: `.\gradlew.bat build --console plain`
- Run all tests: `.\gradlew.bat test --console plain`
- Run the application: `.\gradlew.bat :desktop:run --console plain`
- Run a single test: not applicable at the moment because there are no test classes under `core/src/test` or `desktop/src/test`

Notes:

- `:desktop:run` sets the working directory to `assets`, which is required for LibGDX file loading.
- `test` currently reports `NO-SOURCE` because no tests exist yet.

## Development Workflow (step-by-step, mandatory)
1. Explore the relevant code and assets before editing. Read the touched packages, loaders, and JSON files first.
2. Create a short plan that names the files/packages you will change.
3. Create a new branch with a name describing changes starting from the current commit in master.
3. Make minimal, focused changes in the correct module. Keep `desktop` thin and prefer changes in `core`.
4. Run validation after changes:
   `.\gradlew.bat test --console plain`
5. If you changed build wiring, launcher code, dependencies, or startup flow, also run:
   `.\gradlew.bat build --console plain`
6. Fix any failures before finalizing. Do not leave the tree in a failing state.
7. Show the diff before finalizing:
   `git diff --stat`
   `git diff -- AGENTS.md`
   Replace `AGENTS.md` with the files you actually changed when doing normal feature work.
8. Add all the changed and new files to git.

## Codebase Conventions
Follow the existing package boundaries in `core/src/main/kotlin/com/faultory/core`.

- Put asset path constants in `core.assets.AssetPaths`.
- Put global runtime constants in `core.config.GameConfig`.
- Put JSON-backed catalog/content models in `core.content` as `@Serializable data class`es.
- Put shop/level layout models in `core.shop` as `@Serializable data class`es.
- Put screen classes in `core.screens`. Screen names end with `Screen`.
- Put time-step or simulation coordinators in `core.systems`. System/coordinator names are noun-based, such as `ProductionDayDirector`.
- Put save models, codecs, and repository implementations in `core.save`.
- Keep physics wrappers in a dedicated `physics` subpackage next to the owning domain package.
- Keep `desktop/src/main/kotlin/com/faultory/desktop/DesktopLauncher.kt` as a thin launcher only. Do not move gameplay logic into `desktop`.

Existing naming patterns:

- Data models are nouns: `ShopCatalog`, `ProductDefinition`, `ShiftSnapshot`.
- Loaders are explicit about source and payload: `JsonShopCatalogLoader`, `JsonShopBlueprintLoader`.
- Singleton configuration holders use `object`: `GameConfig`, `AssetPaths`, `FaultoryJson`.

How new features are added in this repository today:

- Add or extend a domain model in `core.content`, `core.shop`, `core.systems`, or `core.save`.
- If the model is asset-backed, add or update the JSON file in `assets/` and wire its path in `AssetPaths`.
- If loading changes, update the matching `Json...Loader`.
- If the feature affects startup, wire it through `FaultoryGame` and `BootScreen`.
- If the feature affects rendering or gameplay flow, update or add a screen in `core.screens`.

There are no repositories in the Spring Data sense. The only repository pattern currently present is `SaveRepository` in `core.save`.

## Rules & Constraints (critical)
- Do not introduce a REST/API/server layer unless the user explicitly asks for one. This repository has no backend framework.
- Do not introduce new dependencies without strong justification. If a dependency is required, add it through `gradle/libs.versions.toml` and the relevant module `build.gradle.kts`.
- Do not refactor large parts of the codebase unless explicitly asked.
- Do not rename packages, assets, or JSON fields unless the task requires it. JSON names are part of the current content/save format.
- Do not change the desktop launcher contract unless startup behavior actually needs to change.
- Do not put new gameplay/domain code in `desktop`.
- Do not revive or repurpose the currently empty `core.world` package unless the task explicitly calls for it. The active domain packages are `core.shop`, `core.content`, `core.systems`, and `core.save`.

## Testing Rules
- Always run `.\gradlew.bat test --console plain` after changes.
- If you touched startup flow, build logic, Gradle files, dependencies, or launcher code, also run `.\gradlew.bat build --console plain`.
- Never ignore failing tests or failing builds.
- Add tests for new non-trivial logic when appropriate, especially for pure logic in `core.systems`, `core.save`, and JSON codec/loader behavior.
- Place new tests under `core/src/test/kotlin/com/faultory/core/...` unless the behavior is truly desktop-launcher-specific.
- There are currently no integration-test source sets or test fixtures. Keep new tests simple and local to the module.

## Common Tasks Playbooks
Adding a new REST endpoint:
This is not applicable to the current repository. There is no HTTP framework, controller layer, or server module. If a task asks for an endpoint, stop and clarify whether a new backend module is actually desired before adding any network stack.

Modifying an entity and propagating changes:
This repository has no JPA entities. Treat `@Serializable` domain models as the data contracts.

- Update the owning data class in `core.content`, `core.shop`, or `core.save`.
- Update the matching JSON asset files under `assets/` if the model is asset-backed.
- Update the matching loader if the JSON root structure changed.
- Update bootstrap defaults in `GameSave.bootstrap()` if save defaults are affected.
- Update any screen or system code that reads the changed fields.
- Run `.\gradlew.bat test --console plain` and then `.\gradlew.bat build --console plain`.

Performing a safe refactor:

- Limit the refactor to one package or one responsibility at a time.
- Preserve public class names, JSON field names, and asset paths unless the task explicitly requires breaking changes.
- Prefer moving logic inside `core` without changing `desktop`.
- After the refactor, run `.\gradlew.bat test --console plain` and `.\gradlew.bat build --console plain`.
- Review the final diff to ensure only the intended module/package changed.

## Anti-Patterns (strict)
- Large-scale refactoring without a direct request
- Introducing Spring, Ktor, Hibernate, Retrofit, or any other new framework speculatively
- Adding code to `app/` or `utils/`
- Touching both `core` and `desktop` when the change is purely gameplay/domain logic
- Moving gameplay logic into `desktop`
- Renaming JSON fields or asset file paths casually
- Editing unrelated packages while implementing a focused change
- Treating this repository like a layered enterprise backend when it is currently a small LibGDX game scaffold
