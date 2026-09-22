# 1vs1

Minecraft の 1 対 1 アリーナ PvP プラグイン。

## Commands

```sh
./gradlew clean build --warning-mode all        # コンパイル + JUnit テスト + jar
nix develop                                     # JDK 21 + actrun のシェル
actrun workflow run .github/workflows/ci.yml    # CI をローカル実行
actrun lint                                     # workflow の静的チェック
```

## Architecture

- 依存方向は `infrastructure → application → domain`(内向きのみ。`ArchitectureTest` で強制)。
  `OneVsOnePlugin` が composition root として手動で全依存を配線する
- application は `port/` の interface 経由で外部と接続する。詳細仕様は docs/ を参照

## Domain

- immutable な値のみ置く
- 不変条件を持つドメイン値はコンストラクタを private にし、companion のファクトリ関数
  (`of`/`new`/`restored`)でのみ生成する(操作の結果を表す型など、不変条件を持たない
  型は対象外)。呼び出し側が与える必要のない値(新規 id 等)はファクトリ内部で生成し、
  既存 id の再構築は `restored` か id 引数あり `new` に分離
- 値からの純粋導出で状態を持たない判定関数(`TeleportRestriction.allows`、
  `DamageAdmission.allows` 等)はドメインに置いてよい
- 1 概念 1 ファイル

## Tests

- アサーションは `kotlin.test` を使う(`org.junit.jupiter.api.Assertions` は使わない)
- domain/application は純粋テスト + fake(`TestApp` 経由)。
  infrastructure は MockBukkit(`TestEnv` で mock + 手動配線)で実状態をアサートする
- リスナーテストは `env.registerListeners()` で登録し `env.fire(event)` の
  実ディスパッチ経由で検証する。ハンドラメソッドの直接呼び出しはしない
  (`@EventHandler` の登録忘れや独自 HandlerList の取りこぼしを検出できないため)
- イベント生成は実アクション・simulate を優先する: `PlayerSimulation`
  (`PlayerMock.simulate*` は委譲シムで deprecated)、`simulateDamage` +
  実 `DamageSource.builder`、`disconnect()`、`teleport()`、`reconnect()`。
  simulate が無いイベントのみフィクスチャで構築して `fire` する
- 発火済みイベントの検証は `env.assertFired<T> { }`(MockBukkit の
  assertEventFired 系は deprecated)
- MockK は障害注入・MockBukkit 未実装 API 等の限定用途のみ
- テストヘルパー(fake/fixture/TestApp/TestEnv 等)は各層の `fixtures/` サブパッケージに隔離する

## Style

- コメントは命名・シグネチャから読み取れる内容を繰り返さない。意図・制約・非自明な経緯のみ書く
- 完全修飾名を書かず、import で解決する
