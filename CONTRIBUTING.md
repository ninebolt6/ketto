# Contributing

Issues and pull requests are welcome.

## Getting started

```sh
./gradlew clean build --warning-mode all      # compile + tests + jar
nix develop                                   # shell with JDK 21 + actrun
actrun workflow run .github/workflows/ci.yml  # run CI locally
actrun lint                                   # static check of workflows
```

Requires JDK 21 (see `.java-version`).

Kotlin style is enforced by ktlint (see `.editorconfig`). The nix devShell
installs a lefthook pre-commit hook that runs `./gradlew ktlintFormat` and
re-stages fixed files; without nix, install lefthook and run `lefthook install`
once. CI blocks unformatted code via `ktlintCheck`; run it or
`./gradlew ktlintFormat` to verify manually.

## Pull requests

- Keep changes focused; one concern per PR
- CI runs `./gradlew clean build`, which treats warnings as errors
- If the change alters observable behavior, update `README.md`,
  `config.yml`, or `messages/*.yaml` accordingly
