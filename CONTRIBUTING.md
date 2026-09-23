# Contributing

Issues and pull requests are welcome.

## Getting started

```sh
./gradlew clean build --warning-mode all   # compile + tests + jar
```

Requires JDK 21 (see `.java-version`). With Nix installed, `nix develop`
provides a shell with the right toolchain and `actrun` for running the CI
workflow locally:

```sh
actrun workflow run .github/workflows/ci.yml
```

## Conventions

The repository's architecture and style rules are documented in
[AGENTS.md](AGENTS.md) — please read it before making changes. In short:

- Layered architecture: `infrastructure → application → domain`, enforced by
  `ArchitectureTest`
- Immutable domain values created through companion factories
- Tests use `kotlin.test`, fakes, and MockBukkit (`TestEnv`); drive listener
  behavior through real player actions rather than direct handler calls

## Pull requests

- Keep changes focused; one concern per PR
- CI runs `./gradlew clean build`, which treats warnings as errors
- If the change alters observable behavior, update `README.md`,
  `config.yml`, or `lang/messages_*.yml` accordingly
