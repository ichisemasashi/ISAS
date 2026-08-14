# 実ユーザーUT記録

このディレクトリは、Phase 1実ユーザーUTの匿名記録と再現可能な集計に使う。実施方法と判定基準は[`docs/実ユーザーUT実施手順.md`](../../docs/実ユーザーUT実施手順.md)を正本とする。

## 構成

- `templates/participants.csv`：匿名参加者と試験条件
- `templates/tasks.csv`：タスクごとの観測値
- `templates/sus.csv`：SUS 10問の回答
- `templates/findings.csv`：観察・重大度・改善追跡
- `analyze_ut.py`：入力検証、SUS計算、ゲート判定
- `test_analyze_ut.py`：集計規則の回帰テスト
- `results/`：実ラウンドの匿名結果。個人情報・同意原本・録画は置かない

空テンプレート自体は実施証跡ではない。実参加者結果がない状態で合格レポートを作らない。

`participants.csv`の`cohort`は`worker`、`older_worker`、`technical_intern`のいずれかを使う。真偽値は`true`／`false`、SUSは各問1〜5で記録する。`consent_record_id`はリポジトリ外の同意原本を照合する無意味な管理IDとし、氏名や従業員番号を使わない。

```bash
python3 -m unittest discover -s research/ut -p 'test_*.py'
python3 research/ut/analyze_ut.py research/ut/results/round-01 --output research/ut/results/round-01/report.md
```

入力不足・重複・未知の参加者、範囲外SUSはエラーにする。閾値未達の正しい集計は終了コード1、全ゲート合格は0を返す。
