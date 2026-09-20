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
  `ArenaApplicationService`(参加/退出/勝敗入口・ライフサイクルの facade)、
  `MatchProgressionService`(カウントダウン/ラウンド遷移/決着/中断の進行機構。
  タイマー所有。ApplicationService から一方向に委譲される)、
  `PlayerRecoveryService`(未復元バックアップの台帳・復元)、`ArenaAdministrationService`、
  `ArenaRegistry`(共有レジストリ)。`port/` に repository(`ArenaRepository`・
  `LobbyRepository`・`ArenaSignRepository`・`MatchStateRepository`・`PlayerStatsRepository`)・
  kit(`KitPort`)・backup(`InventoryBackupPort`)・player・scheduler・presentation・failure の
  各 interface と DTO(`BackupRef` 等)。1 アダプターが複数 port を実装してよい
  (`PaperEquipmentAdapter` = キット+バックアップ)
- `.../infrastructure/paper/` — Bukkit/Paper 実装。`ArenaListener`、`ArenaSignListener`、
  `Messages`、`PaperPlayerAdapter`/`PaperPlayerLookup`(QuitEvent 中の切断者解決)、
  `command/`(TabExecutor `OneVsOneCommand` + `Subcommand` ルーティング。権限・引数判定は
  各ハンドラ、`arena` 名前空間は `CommandGroup` の再帰)、
  `PaperEquipmentAdapter` + `PaperInventorySnapshot`(ItemStack をここに閉じ込める)、
  `PaperScheduler`、`PaperMatchPresentation`、`PluginFailureReporter`
- `.../infrastructure/persistence/` — `YamlStore`(ファイル配置・共通 I/O・
  コーデックのみ) + `YamlBackupStore`(players.yml の inv.\*)/`YamlKitStore`
  (arena/<name>.yml の inventory)/`YamlArenaRepository`/`YamlLobbyRepository`/
  `YamlSignRepository`/`YamlMatchStateRepository`/`YamlPlayerStatsRepository`。
  1 ファイルを複数クラスが共有する場合、セクション所有者は各クラス名で区別する
- `src/main/resources/` — plugin.yml(version は processResources で展開), config.yml,
  `lang/messages_<lang>.yml`(MiniMessage 文言。`Messages` が Msg キー+引数を宛先ロケールで
  描画。`language: auto` でクライアントロケール、共有面は `default-language`)
- `src/test/kotlin/...` — domain/application は純粋テスト + fake、infrastructure は
  JUnit5 + MockK(モック Server/Player/Scheduler、静的 Bukkit を mockkStatic)の TestEnv 統合。
  `ArchitectureTest` が内側層の禁止参照を検査。
  テストヘルパー(fake/fixture/TestApp/TestEnv 等)は各層の `fixtures/` サブパッケージに隔離する
- 状態はメモリ(domain の `ArenaMatch` + `ArenaRegistry`)、YAML は永続化専用。詳細仕様は docs/ を参照
- インベントリスナップショットは**マッチ開始時**(初期カウントダウン終了、キット適用直前)に
  両者分を取得し、一括保存に成功してからキットを適用する。参加登録・開始前の退出では持ち物を変更しない。
  (バックアップ無しは「キット未適用」を意味し、退出時に復元を行わない。
  例外: 前回マッチの pending restore 完了は再参加時にインベントリへ適用し得る)
- 禁止: domain/application で `org.bukkit`・`io.papermc`・`net.kyori`・`YamlConfiguration`・
  `java.io.File`・`java.nio.file`・`infrastructure` パッケージ参照(ArchitectureTest で検出)

## スタイル / Style

- コメントは命名・シグネチャから読み取れる内容を繰り返さない。意図・制約・非自明な経緯のみ書く
- 完全修飾名を書かず、import で解決する
