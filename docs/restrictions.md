# 参加者への制約(イベント処理)

アリーナに参加中のプレイヤーへ適用する制約。状態ごとの可否判定は
`domain/ParticipantRestrictions` に集約し、リスナーはイベント変換のみ行う。
リスナーは責務別に3分割されている: `ArenaMatchListener`(死亡・ダメージ・
切断・参加・移動・コマンドの試合進行系)、`ArenaGuardListener`(ブロック・
インベントリ・エンティティ操作の保護系)、`ArenaTeleportListener`
(テレポート・乗車の移動制限系)。参加看板は `ArenaSignListener` が担う。

## 状態別の制約

| 状態             | X/Z 移動凍結 | ダメージ         | ブロック破壊 | ブロック設置 | アイテム持ち出し         | 拾得・獲得 | テレポート          | コマンド |
| ---------------- | ------------ | ---------------- | ------------ | ------------ | ------------------------ | ---------- | ------------------- | -------- |
| `WAITING`        | -            | -                | -            | -            | -                        | -          | -                   | 禁止     |
| `ONEMORE`        | -            | -                | -            | -            | -                        | -          | -                   | 許可     |
| `COUNTDOWN`      | -            | -                | -            | -            | -                        | -          | パール+内部移送のみ | 禁止     |
| `ROUNDCOUNTDOWN` | 凍結         | 全て無効         | 禁止         | 禁止         | 禁止(ドロップ・預け入れ) | 禁止       | 内部移送のみ        | 禁止     |
| `INGAME`         | -            | 対戦相手のみ有効 | 禁止         | 禁止         | 禁止(ドロップ・預け入れ) | 禁止       | パール+内部移送のみ | 禁止     |

## イベント別の挙動

### PlayerDeathEvent(HIGH)

- 参加者の死亡時: `keepInventory = true` でドロップを空にする。
- 経験値も失わせない: `droppedExp = 0` でドロップさせず、`keepLevel = true` でリスポーン後もレベル・経験値を保持する。
- 敗北として受理されればラウンド/マッチ決着処理へ。受理されない場合(状態不適・解決中)は次 tick でリスポーンのみ予約。

### EntityDamageEvent(HIGH)

- `EntityDamageByEntityEvent`(エンティティ起因)の場合: `damageSource.causingEntity` で責任者(投射物の射手や設置者)を解決し、被害者・加害者のどちらかが制限状態の参加者なら「INGAME で同一マッチの対戦相手または本人」由来のみ許可し、それ以外はキャンセル。MOB・第三者・別マッチからの干渉と、参加者→部外者/MOB への攻撃を塞ぐ。
- 非エンティティダメージ(落下・火・溶岩・窒息など)は帰属できないため、ダメージ無効の状態でのみキャンセルし、INGAME では従来通り敗北として受理する。

### PlayerMoveEvent

- `PlayerTeleportEvent` は独自の HandlerList を持つためこのハンドラには届かない(テレポートは ArenaTeleportListener で制限する)。
- X/Z 移動凍結の状態でブロック座標が変わる移動は `setTo(from)` で差し戻す。
- `INGAME` / `ROUNDCOUNTDOWN` かつ 2 人在籍中に移動先ワールドの最低高度以下へ落下した場合は落下敗北として解決する。

### PlayerTeleportEvent / PlayerPortalEvent

- 参加者のテレポートを状態別の原因制限で判定する(パール+内部移送のみ / 内部移送のみ / 制約なし)。
- プラグイン自身の移送は同期発火するイベントを識別するマーカーで区別する。
- `PlayerPortalEvent` は独自の HandlerList を持つため個別にハンドリングする。
- 移動凍結中は `VehicleEnterEvent` で乗車も遮断する。

### BlockBreakEvent

- ブロック破壊禁止の状態ではキャンセル。

### BlockPlaceEvent

- ブロック設置禁止の状態ではキャンセル。破壊禁止中は撤去できないため、アリーナの汚染と籠城を防ぐ。
- 火打ち石は設置ではなく着火として許可する。
- `EntityPlaceEvent` / `HangingPlaceEvent`(ボート・トロッコ・防具立て・額縁・絵画の設置)、`PlayerBucketEmptyEvent`(液体設置)、`BlockFertilizeEvent`(骨粉による成長)、`SignChangeEvent`(未ワックス看板の書き換え)も同じ制約でキャンセル。
- `PlayerBucketFillEvent` / `PlayerBucketEntityEvent`(バケツ回収・捕獲)はブロック破壊と同じ制約でキャンセル。

### PlayerDropItemEvent

- ドロップ禁止の状態ではキャンセル。装備交換後はインベントリが開始時バックアップで上書きされるため、落とした装備を持ち出されるのを防ぐ。

### PlayerInteractEvent

- 参加看板の右クリックは ArenaSignListener が処理する(参加動作は signs.md)。
- ArenaGuardListener 側では、インベントリ移譲禁止の状態で預け入れ可能なブロック(コンテナ類・エンダーチェスト・ベッド・リスポーンアンカー・植木鉢)とのインタラクトを拒否し、ブロック設置禁止の状態ではスポーンエッグの使用を拒否する。

### PlayerInteractEntityEvent / PlayerInteractAtEntityEvent / PlayerArmorStandManipulateEvent

- インベントリ移譲禁止の状態で、インベントリを持つエンティティ(村人・チェスト付きトロッコ等)・額縁・防具立てとのインタラクトをキャンセル。
- Bukkit はイベントクラス毎に HandlerList が分かれるため、サブクラスは個別にハンドリングする。

### InventoryClickEvent / InventoryDragEvent

- インベントリ移譲禁止の状態で、外来インベントリ(持ち物画面以外の `CRAFTING`/`PLAYER` 以外)が開いている間の操作を全てキャンセルする(インタラクト側を抜けた二番手防衛)。

### EntityPickupItemEvent / PlayerAttemptPickupItemEvent / PlayerPickupArrowEvent

- 拾得禁止の状態ではキャンセル。矢・トライデントの回収も含む(観戦者が射込んだ物資の受け取りを塞ぐ)。

### PlayerHarvestBlockEvent

- 拾得禁止の状態ではキャンセル(ベリー系収穫は拾得イベントを介さず直接インベントリへ入る)。

### BlockDispenseArmorEvent

- ディスペンサーが制限状態の参加者へ防具を装着するのをキャンセルする。

### PlayerCommandPreprocessEvent

- コマンド禁止の状態ではキャンセルし「コマンドは使用できません！」を返す。
- `ONEMORE` 待機中のみコマンドを許可する。

### PlayerQuitEvent

- 切断中プレイヤーは `Server` から取得できなくなるため、イベントの `Player` を同期処理中だけ解決できるスコープで参加/復元処理を実行する。
- 未参加でも未復元バックアップがあれば復元してから切断させる。

### PlayerJoinEvent

- 未参加で未復元バックアップがあれば復元する。

## 既知の残存リスク

- 第三者のスプラッシュ/残留ポーションによるバフ・デバフ付与(ダメージイベントではなく帰属判定が複雑なため対象外)。
- ウィンドチャージ等のノックバック(ダメージは遮断されるが吹き飛ばしが残る可能性)。
- 開放アリーナへの第三者の溶岩流入など、環境経由の妨害は帰属不能のためアリーナの物理的囲いや領域保護に委ねる。
- 既存の TNT への着火(火打ち石は許可)によるアリーナブロックの爆発破壊はアリーナ設計依存。
