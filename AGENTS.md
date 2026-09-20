# 1vs1

Minecraft の 1 対 1 アリーナ PvP プラグイン。

## Commands

```sh
./gradlew clean build --warning-mode all   # コンパイル + JUnit テスト + jar
```

## Architecture

- 依存方向は `infrastructure → application → domain`(内向きのみ。`ArchitectureTest` で強制)。
  `OneVsOnePlugin` が composition root として手動で全依存を配線する
- application は `port/` の interface 経由で外部と接続する。詳細仕様は docs/ を参照

## Domain

- immutable な値のみ置く
- ドメイン値は private ctor + companion ファクトリ(`of`/`new`/`restored`)でのみ生成。
  呼び出し側が与える必要のない値(新規 id 等)はファクトリ内部で生成し、既存 id の再構築は
  `restored` か id 引数あり `new` に分離
- 1 概念 1 ファイル

## Tests

- domain/application は純粋テスト + fake(`TestApp` 経由)。
  infrastructure は MockBukkit(`TestEnv` で mock + 手動配線)で実状態をアサートする
- MockK は障害注入・MockBukkit 未実装 API 等の限定用途のみ
- テストヘルパー(fake/fixture/TestApp/TestEnv 等)は各層の `fixtures/` サブパッケージに隔離する

## Style

- コメントは命名・シグネチャから読み取れる内容を繰り返さない。意図・制約・非自明な経緯のみ書く
- 完全修飾名を書かず、import で解決する
