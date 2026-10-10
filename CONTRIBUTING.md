# Contributing

Issues and pull requests are welcome.

## Getting started

```sh
./gradlew clean build --warning-mode all      # compile + tests + jar
./gradlew clean build -PpaperNext             # build/test against the next Paper line
nix develop                                   # dev shell with the project JDK, actrun, lefthook
actrun workflow run .github/workflows/ci.yml  # run CI locally
actrun lint                                   # static check of workflows
```

Two build variants exist. The default compiles against stable Paper and emits
that line's bytecode target even on newer JDKs; `-PpaperNext` swaps in the
next Paper line's paper-api and MockBukkit (the `next-*` entries in
`libs.versions.toml`) and requires the newer JDK in the `ci.yml` matrix. The
nix devShell provides a JDK that builds both variants.

`actrun` skips `actions/setup-java` (see `actrun.toml`), so each matrix leg
runs on the JDK that launches Gradle. With nix installed, actrun wraps `run:`
steps in the flake's devShell; without nix, supply a JDK that can build every
leg yourself.

Kotlin style is enforced by ktlint (see `.editorconfig`). The nix devShell
installs a lefthook pre-commit hook that runs `./gradlew ktlintFormat` and
re-stages fixed files; without nix, install lefthook and run `lefthook install`
once. CI blocks unformatted code via `ktlintCheck`; run it or
`./gradlew ktlintFormat` to verify manually.

## Conventions

Architecture, domain, test, and style rules live in [AGENTS.md](AGENTS.md).

## Pull requests

- Keep changes focused; one concern per PR
- CI runs `./gradlew clean build` on each JDK variant in the matrix, treating
  warnings as errors
- If the change alters observable behavior, update `README.md`,
  `config.yml`, or `messages/*.yaml` accordingly
