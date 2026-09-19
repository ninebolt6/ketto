# 参加者への制約(イベント処理)

アリーナに参加中のプレイヤーへ適用する制約。状態ごとの可否判定は
`domain/ParticipantRestrictions` に集約し、リスナーはイベント変換のみ行う。

## 状態別の制約

| 状態 | X/Z 移動凍結 | ダメージ無効 | ブロック破壊禁止 | コマンド禁止 |
|---|---|---|---|---|
| `WAITING` | - | - | - | 禁止 |
| `ONEMORE` | - | - | - | 許可 |
| `COUNTDOWN` | - | - | - | 禁止 |
| `ROUNDCOUNTDOWN` | 凍結 | 無効 | 禁止 | 禁止 |
| `INGAME` | - | - | 禁止 | 禁止 |

## イベント別の挙動

### PlayerDeathEvent(HIGH)

- 参加者の死亡時: `keepInventory = true` でドロップを空にする。
- 敗北として受理されればラウンド/マッチ決着処理へ。受理されない場合(状態不適・解決中)は次 tick でリスポーンのみ予約。

### EntityDamageEvent(HIGH)

- ダメージ無効の状態ではキャンセル。

### PlayerMoveEvent

- `PlayerTeleportEvent` は除外。
- X/Z 移動凍結の状態でブロック座標が変わる移動は `setTo(from)` で差し戻す。
- `INGAME` / `ROUNDCOUNTDOWN` かつ 2 人在籍中に `y <= 0` へ落下した場合は落下敗北として解決する。

### BlockBreakEvent

- ブロック破壊禁止の状態ではキャンセル。

### PlayerInteractEvent

- 右クリック + メインハンド + 看板のみ処理(参加動作は signs.md)。

### PlayerCommandPreprocessEvent

- コマンド禁止の状態ではキャンセルし「コマンドは使用できません！」を返す。
- `ONEMORE` 待機中のみコマンドを許可する。

### PlayerQuitEvent

- 切断中プレイヤーは `Server` から取得できなくなるため、イベントの `Player` を同期処理中だけ解決できるスコープで参加/復元処理を実行する。
- 未参加でも未復元バックアップがあれば復元してから切断させる。

### PlayerJoinEvent

- 未参加で未復元バックアップがあれば復元する。
