# 1vs1

Paper 1.21.x 向け 1 対 1 アリーナ PvP プラグイン。Kotlin 2.4.20 / Java 21 / Gradle 9.7.1 (Kotlin DSL)。
フルスクラッチ実装。

## ビルド環境 / Toolchain

JDK 21 を使用すること。Gradle wrapper は 9.7.1 + `distributionSha256Sum` 固定済み
(`gradle/wrapper/gradle-wrapper.properties`)。

## コマンド / Commands

```sh
./gradlew --version
./gradlew clean build --warning-mode all   # コンパイル + JUnit テスト + jar
jar tf build/libs/1vs1-1.0.0.jar           # kotlin/ は同梱、org/bukkit・io/papermc は非含有
git diff --check
```

## 構成 / Layout

依存方向は `infrastructure → application → domain`(内向きのみ)。`OneVsOnePlugin` が
composition root として手動で全依存を配線する(DI フレームワーク不使用)。

- `src/main/kotlin/net/ninebolt/onevsone/` — `OneVsOnePlugin.kt`(composition root のみ)
- `.../domain/` — 純粋 Kotlin/JDK のみ。`ArenaMatch` 集約(immutable: 各操作は新状態を持つ
  `Transition` を返し、呼び出し側がレジストリへ書き戻す。状態遷移・勝敗規則・世代トークン)、
  `ArenaDefinition`(immutable data class)、`ParticipantRestrictions`、`PlayerStats`、
  識別子(`ArenaId`/`MatchId`)
- `.../application/` — domain + `application/port` のみに依存。
  `ArenaApplicationService`(参加/開始/決着/終了/中断のオーケストレーション)、
  `PlayerRecoveryService`(未復元バックアップの台帳・復元)、`ArenaAdministrationService`、
  `ArenaRegistry`(共有レジストリ)。`port/` に repository・equipment・player・scheduler・
  presentation・failure の各 interface と DTO(`BackupRef` 等)
- `.../infrastructure/paper/` — Bukkit/Paper 実装。`ArenaListener`、`OneVsOneCommand`、
  `Messages`、`PaperPlayerAdapter`/`PaperPlayerLookup`(QuitEvent 中の切断者解決)、
  `PaperEquipmentAdapter` + `PaperInventorySnapshot`(ItemStack をここに閉じ込める)、
  `PaperScheduler`、`PaperMatchPresentation`、`PluginFailureReporter`
- `.../infrastructure/persistence/` — `YamlStore`(共通 I/O と backup コーデック) +
  `YamlArenaRepository`/`YamlMatchStateRepository`/`YamlPlayerStatsRepository`
- `src/main/resources/` — plugin.yml(version は processResources で展開), config.yml
- `src/test/kotlin/...` — domain/application は純粋テスト + fake、infrastructure は
  JUnit5 + Mockito(モック Server/Player/Scheduler、静的 Bukkit を mockStatic)の TestEnv 統合。
  `ArchitectureTest` が内側層の禁止参照を検査
- 状態はメモリ(domain の `ArenaMatch` + `ArenaRegistry`)、YAML は永続化専用。詳細仕様は docs/ を参照
- インベントリスナップショットは**マッチ開始時**(初期カウントダウン終了、キット適用直前)に
  両者分を取得し、一括保存に成功してからキットを適用する。参加登録・開始前の退出では持ち物を変更しない。
  (バックアップ無しは「キット未適用」を意味し、退出時に復元もフォールバックアイテムも行わない。
  例外: 前回マッチの pending restore 完了は再参加時にインベントリへ適用し得る)
- 禁止: domain/application で `org.bukkit`・`io.papermc`・`net.kyori`・`YamlConfiguration`・
  `java.io.File`・`java.nio.file`・`infrastructure` パッケージ参照(ArchitectureTest で検出)
