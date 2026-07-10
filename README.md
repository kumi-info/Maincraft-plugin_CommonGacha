# Gacha

**バージョン: v1.6.0**

プリセットを重み付き抽選し、当選結果に応じた**コンソールコマンドを実行**する汎用ガチャプラグインです。TikTok 妨害配信で、視聴者の操作（ギフト等）に応じて妨害／救済イベントを抽選する用途で作成しています。

---

## 概要・抽選の流れ

`/gacha preset <id>` を実行すると、次の順で演出が進みます。

0. **ガチャ名表示**（`animation.name-title-seconds` 秒・既定2秒）… `presets.<id>.name` をタイトル表示し、何のガチャか分かるようにする（サブタイトルなし）。色は `animation.name-title-color`（preset 個別は `presets.<id>.name-color`）で変更可。
1. **スロット風アニメ**（`animation.roll-seconds` 秒）… 候補をランダムに高速表示し、終端で減速。星（⭐）もレア度色で表示。
2. **レア度ランク表示**（SSR 等を大きく表示）。
3. **当選内容表示**（サブタイトルは星のみ・`[HR]` 等のランク表記なし）＋ 全体アナウンス ＋ **commands 実行**。

レア度は**当選確率から自動判定**されます（エントリ側に rarity は書きません）。確率 = `そのエントリの weight ÷ プリセット内 weight 合計 × 100`。

---

## コマンド

| コマンド | 説明 |
|---|---|
| `/gacha preset <id>` | プリセット `<id>` を1回抽選して演出・コマンド実行 |
| `/gacha list` | プリセット一覧を表示 |
| `/gacha reload` | `config.yml` を再読み込み |
| `/gacha test <1-5>` | **演出確認用**：指定の星ランク(1〜5)の演出だけ再生（commands 非実行・確率不変・ガチャ名なし） |
| `/gacha force <preset> <key>` | **確定当選**：乱数を使わず指定エントリを当選させる（commands 実行・本番同等・ガチャ名あり） |

- 権限: `gacha.use`（デフォルト op）
- タブ補完対応

---

## レア度ランク（自動判定）

当選確率に応じて 5 段階で自動的に決まります（確率が低いほど高ランク）。

| ランク | 当選確率 | ⭐ | 色 |
|---|---|---|---|
| HR | 1% 以下 | ⭐5 | GOLD |
| SSR | 5% 以下 | ⭐4 | LIGHT_PURPLE |
| SR | 15% 以下 | ⭐3 | AQUA |
| R | 40% 以下 | ⭐2 | GREEN |
| N | それ以外 | ⭐1 | GRAY |

判定は `rarity-tiers` の `max-chance`（その確率%以下ならそのランク）で行います。

---

## config.yml

```yaml
animation:
  roll-seconds: 5            # スロット回転の秒数（0 でアニメ無し・即ランク→中身）
  name-title-seconds: 2      # 抽選開始前にガチャ名を表示する秒数（0 で表示しない）
  name-title-color: gold     # ガチャ名タイトルの色（black/blue/aqua/red/yellow/white 等）
  # ※ presets.<id>.name-color で preset ごとに色を上書き可

messages:
  win: "§6[ガチャ] §f%player% §6は §e%name% %stars% §6を引き当てた！"

rarity-tiers:             # 5段階（rank / max-chance / stars / color）
  - { rank: "HR",  max-chance: 1.0,   stars: 5, color: "GOLD" }
  # ... SSR / SR / R / N

presets:
  '1':
    name: "サンプルガチャ"     # ← ガチャ名（手順0で表示・list 用説明）
    entries:
      apple:                  # ← 任意のキー（プリセット内で一意ならOK・別途登録不要）
        name: "りんご当たり"   # ← 当選名（省略時はキー名）
        weight: 30.0          # ← 相対確率（合計に対する比率で当選率が決まる）
        commands:             # ← 当選時にコンソール権限で実行（%player% 置換）
          - "give %player% apple 5"
```

- **プレースホルダ**: `%player%`（実行者）/ `%name%`（当選名）/ `%rank%`（ランク）/ `%stars%`（⭐文字列）。
- エントリキーは任意の半角英数で一意ならOK。`name` 省略時はキー名が表示名になります。

---

## 同梱プリセット例

- `'1'` サンプルガチャ（diamond / iron / apple / nothing）
- `'2'` **タイマーガチャ**：5 エントリ＝5 ランク。各 commands は `countdown N`。妨害(15秒以上)が救済(15秒未満)より出やすい配分（sec15≒53% / sec20≒30% / sec10≒12% / sec30≒4% / sec3≒0.8%）。
- `'3'` 救済ガチャ / `'4'` 妨害ガチャ（現状サンプルのコピー・要調整）。

---

## インストール / 反映

1. ビルドした `Gacha.jar` を `plugins/` に配置。
2. サーバー再起動（または `/reload confirm`）で有効化。`config.yml` は初回起動時に自動生成。
   - 既存サーバーに `name-title-*` を追加する場合は、稼働中の `plugins/Gacha/config.yml` に手動追記して `/gacha reload`（未記載でも既定2秒・gold で動作）。

---

## 更新履歴

| バージョン | 変更点 |
|---|---|
| v1.6.0 | スロット回転中の**星（⭐）をレア度色で表示**。当選／テスト演出のサブタイトルから **`[HR]` 等のランク表記を削除**（星のみ表示に）。 |
| v1.2.0 | 抽選開始前に**ガチャ名タイトル表示**（`animation.name-title-seconds`・既定3秒）追加。 |
| v1.3.0 | ガチャ名タイトルの既定表示秒数を 3→**2秒**に変更。**色を config 化**（`animation.name-title-color`／`name-title-sub-color`、preset 個別 `presets.<id>.name-color`）。 |
| v1.4.0 | ガチャ名タイトルの**サブタイトル（「ガチャ抽選」）を廃止**。`name-title-sub-color` 設定も削除。 |
| v1.5.0 | 当選アナウンス(`messages.win`)に**`%presets%`(=`%preset%`)プレースホルダ追加**＝ガチャ名(`presets.<id>.name`)を表示可能に。 |
| v1.1.0 | `test`（演出確認）/ `force`（確定当選）コマンド追加。rarity-tiers を確率連動の5段階に確定。preset `'2'` タイマーガチャ追加。 |

---

> 📌 **メンテナンス方針**: 機能を変更してバージョンを上げたときは、必ず本 README の「バージョン」表記・コマンド表・更新履歴も合わせて更新すること。
