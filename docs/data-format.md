# データフォーマット仕様

すべてプラグインデータフォルダ(`plugins/1vs1/`)配下の YAML。アリーナ状態はメモリ管理で、YAML は永続化専用。

```
plugins/1vs1/
├── config.yml            # 全体設定(管理者編集のみ。プラグインは書き込まない)
├── lobby.yml             # ロビー座標
├── arenalist.yml         # アリーナ名一覧
├── arena/<name>.yml      # アリーナ定義(有効化・スポーン・装備・看板)
├── status/<name>.yml     # アリーナ状態スナップショット
├── status/players.yml    # 参加登録・未復元バックアップ
└── stats/<uuid>.yml      # 戦績
```

保存は常に `*.tmp` へ書き出してから原子的に置き換える(ATOMIC_MOVE 非対応 FS では通常 move)。YAML パース失敗・I/O 失敗は `PersistenceFailure` に変換する。

## config.yml

管理者が編集する設定のみ。プラグインはこのファイルを書き込まない。

```yaml
prefix: "&8[&61vs1&8] " # メッセージ接頭辞(& 形式)
required-wins: 3 # マッチ勝利に必要なキル数
```

## lobby.yml

```yaml
# ロビー(/1vs1 setlobby で設定)
lobby:
  world: world
  x: 0.0
  y: 0.0
  z: 0.0
  yaw: 0.0
  pitch: 0.0
```

## arenalist.yml

```yaml
arenas:
  - <name>
```

- 起動時はこの一覧の順に読み込む。無効名・重複名(大小区別なし)は警告してスキップ。

## arena/<name>.yml

```yaml
enabled: true
spawn1: { world: world, x: 0.0, y: 0.0, z: 0.0, yaw: 0.0, pitch: 0.0 }
inventory:
  armor: [ItemStack, ...] # Bukkit YAML シリアライズ形式
  item: [ItemStack, ...]
sign: # 参加看板(/1vs1 arena setsign で設定)
  world: world
  x: 0.0
  y: 0.0
  z: 0.0
```

- `enabled` / `spawnN` の保存時に `inventory`・`sign` セクションは保持する(それぞれ `setInv`・`setsign` の責務)。
- yaw/pitch は float 精度で保持・読み出す。

## status/<name>.yml

```yaml
status: WAITING # ArenaState 名
players: [<name>, ...] # 参加者名
win: { <name>: <wins> } # 勝数(名前キー)
```

- 状態遷移のたびに上書きする参照用スナップショット。復元には使わない。

## status/players.yml

```yaml
players: [<name>, ...] # 参加登録中のプレイヤー名
arena:
  <name>: <arena> # プレイヤー名 → アリーナ名
inv:
  <name>: # 未復元バックアップ(プレイヤー名キー)
    armor: [ItemStack, ...]
    item: [ItemStack, ...]
    uuid: <uuid> # 所有者 UUID
    id: <uuid> # バックアップ識別子
    match: <uuid> # マッチ識別子
```

- `players` / `arena.*`: 参加登録。退出・終了・中断時に解除する(バックアップ `inv.*` には触れない)。起動時に登録はクリアする。
- `inv.*`: 試合開始時の一括保存。復元完了後に削除する。削除時は `id`(無い場合は `uuid`)が一致する記録だけを消し、名前の再利用で別人のデータを消さない。
- `uuid` 未記録のバックアップでは `playerId` は null として扱う。

## stats/<uuid>.yml

```yaml
win: 0
lose: 0
```

- ファイル不存在は「戦績なし」(null)。破損は `PersistenceFailure`。
