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
