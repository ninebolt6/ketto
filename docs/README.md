# 1vs1 Plugin Specification

## Overview

Specification for **1vs1**, a 1-on-1 arena PvP plugin for Minecraft servers.
Two players who join via a sign are teleported into the arena and fight in
rounds. Results are shown on the scoreboard as round wins, and the match
outcome is recorded as stats (Win/Lose).

## Technical Requirements

| Item                  | Value                    |
| --------------------- | ------------------------ |
| Language              | Kotlin 2.4.20            |
| JVM                   | Java 21                  |
| Build                 | Gradle (Kotlin DSL)      |
| Platform              | Paper 1.21.x (Paper API) |
| Plugin name           | `1vs1`                   |
| Main command          | `/1vs1`                  |
| External dependencies | None                     |

## Document Layout

| File                               | Contents                                                                           |
| ---------------------------------- | ---------------------------------------------------------------------------------- |
| [game-flow.md](game-flow.md)       | Game state transitions, match progression, win/loss handling, teardown, scoreboard |
| [commands.md](commands.md)         | Spec of every `/1vs1` subcommand                                                   |
| [signs.md](signs.md)               | Join/status sign spec                                                              |
| [restrictions.md](restrictions.md) | Restrictions on participating players (event handling)                             |
| [stats.md](stats.md)               | Stats recording/display spec                                                       |
| [data-format.md](data-format.md)   | YAML persistence data structure                                                    |
| [messages.md](messages.md)         | All messages and display text                                                      |

## Terminology

| Term        | Meaning                                                                                |
| ----------- | -------------------------------------------------------------------------------------- |
| Arena       | Unit of management for a battleground. Has 2 spawn points, an equipment set, and signs |
| Participant | A player who entered an arena via a sign (including before teleport)                   |
| Round       | A unit of match play ending with one kill                                              |
| Match       | A set of rounds; ends when one side reaches the required kill count                    |
| Lobby       | Return point after a match or after leaving. Set by an OP command                      |

## Design Principles

1. **In-memory state**: Arena state and participant info live in memory; YAML is only for persistence.
2. **UUID internally**: Players are managed by UUID internally. Persistence files keep their name-based format (see data-format.md).
3. **Layer separation**: Dependency direction is `infrastructure → application → domain` (inward only). `OneVsOnePlugin` is the composition root and wires all dependencies manually (no DI framework).
4. **Invalidation via generation tokens**: Countdowns and deferred callbacks are validated against a `MatchToken` (generation number); stale tasks started before an abort or rejoin are automatically invalidated.
