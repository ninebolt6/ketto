# 戦績(Stats)仕様

## 記録

- マッチ終了時に勝者 `win + 1`、敗者 `lose + 1` を `stats/<uuid>.yml` へ記録。
- 記録キーはプレイヤー UUID。ファイル形式は `win` / `lose` の整数(data-format.md 参照)。
- 記録の失敗は警告ログとして報告するのみで、終了処理(復元・ロビー転送)は継続する。

## 表示(`/1vs1 stats`)

```
Win: <wins>
Lose: <losses>
W/L(勝率): <ratio>
```

- `ratio` = `win / lose` を小数第 2 位 `HALF_UP` で丸めた文字列。
- `lose == 0` のときは `win / 1` として計算する。
- 戦績ファイルが存在しないプレイヤーは「Statsが存在しません」。
