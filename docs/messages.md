# メッセージ・表示文言

全メッセージは `lang/messages_<lang>.yml` の MiniMessage テンプレートとして管理する。
コード側はキーと引数を保持する `Msg` を組み立て、送信時に宛先の言語で描画する。
キーと既定文言の正本は `MessageKeys` と同梱 `messages_ja.yml`/`messages_en.yml` を参照。

## 言語の選択

```yaml
# config.yml
language: auto # auto=各プレイヤーのクライアントロケール。ja/en 等でサーバー固定
default-language: ja # フォールバック + 看板・スコアボード等の共有面
```

- `language: auto` 時、チャット/broadcast は `Player.locale()`(`ja_jp` → 完全一致 → `ja`
  言語部一致)で言語を解決する。未対応ロケールは `default-language` にフォールバック
- 看板・スコアボード・コンソールは共有面のため固定言語(`language` 固定値、
  auto 時は `default-language`)で描画する
- 言語ファイルは `plugins/1vs1/lang/messages_<lang>.yml` に配置。jar 同梱の既定言語を
  基底に dataFolder 側の同名ファイルでキー単位に上書きできる。新言語は
  `messages_<lang>.yml` を置くだけで追加できる。既定言語に存在し当該言語に無いキーは
  起動時に warn され、描画時は既定言語へフォールバックする

## 仕組み

- `MessageKeys`: 全キーを集約。`MessagesTest` が全同梱言語のキー網羅性を検証する
- `Messages` のファクトリ(`joined(name)` 等)は `Msg` を返すだけで描画しない。
  `send(sender, msg)` が宛先ロケールで描画し prefix を前置する
- 引数は `Msg.Str`(プレーンテキスト。`<` を含んでもタグ化しない)と
  `Msg.Nested`(同ロケールで描画される別 Msg。状態表示の埋め込み用)の2種
- プレースホルダは `<name>` 等の MiniMessage タグ。テンプレート側のタグ名と
  `Str`/`Nested` の name が一致する必要がある(未解決プレースホルダはそのまま表示)
