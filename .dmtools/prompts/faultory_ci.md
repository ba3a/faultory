# Faultory — CI environment notes

You run on a Linux GitHub Actions runner, not on the developer's Windows machine. The repository's
`CLAUDE.md` is written for Windows; adapt it as follows.

## Commands

- Use `./gradlew`, never `./gradlew.bat` or `.\gradlew.bat`. Pass `--console plain`.
- Java 21 is installed and the Gradle caches are warm. Do not change JDK or toolchain settings.
- Run only the tests of the code you changed, for example
  `./gradlew :core:test --tests "com.faultory.core.shop.ShopFloorQaTest" --console plain`.
  Do not run the whole `build`, and never run `:desktop:run` or `:editor:run` (no display).
- Run `./gradlew detekt --console plain` before you finish; new code has to come in clean.
- Module tests live in the module they exercise (`core/src/test/...`, `editor/src/test/...`).

## Scope

- Follow `CLAUDE.md` for architecture and the "Constraints" section; it is authoritative.
- Keep the change small and in the existing style. Do not rename JSON fields or asset paths.
- Do not edit `.github/`, `.dmtools/` or `agents/`.

## Issues, labels and pull requests

The machine loop starts work from labels and from the issue and pull request structure, so touching
them starts or stops other runs.

- Never create, close, edit or comment on issues, and never add or remove labels on an issue or a
  pull request — not with `gh`, not with the API, not through any tool. Labels like
  `dmtools:develop` and `dmtools:plan` start new agent runs.
- If the task asks you to split the work into issues, do not do it: say so in your response and
  stop. Splitting an issue is the job of the planning leg (label `dmtools:plan`), which creates the
  steps itself and starts them one at a time.
- Your output goes through the files the instructions name (`outputs/response.md` and the like);
  the pipeline opens and updates the pull request for you.
