# Phase 2 作付計画・高度ガント

| 項目 | 実装 |
|---|---|
| migration | `0014_advanced_planning.sql` |
| 作付情報 | `crop_plan`の作物、品種、計画面積、収量目標を作業指示APIへ投影 |
| template | `work_plan_template`／`work_plan_template_step`。基準日から日offsetと所要時間を展開 |
| 依存関係 | templateの先行stepを`work_instruction_dependency`へ展開。0013のcycle拒否を継続使用 |
| 進捗 | `work_progress_event`へ追記し、`work_instruction.progress_percent`を楽観lock付きで更新 |
| resource競合 | 割当時間rangeの重複とcapacity超過を`resource_conflict` security-invoker viewで導出 |
| 作付進捗 | 作業指示の平均進捗を`crop_plan_progress` security-invoker viewで導出 |
| UI | PCガントとモバイル作業リストが同じ`GET /api/v1/work-instructions`を利用 |

## API

- `GET /api/v1/planning/templates`：利用可能なtemplateと順序付きstepを取得する。
- `POST /api/v1/planning/templates/:id/expand`：`cropPlanId`、`assigneeUserId`、`baseDate`、`expectedVersion`を受け、作業指示、担当、依存、resource割当を1 transactionで生成する。
- `PATCH /api/v1/work-instructions/:id/progress`：`eventUuid`、0〜100の進捗、`expectedVersion`、理由を受け、冪等なprogress eventと現在値を保存する。
- `GET /api/v1/work-instructions`：作物・品種・面積・収量目標、進捗、先行作業、resource、競合を返す。PCとモバイルはこの応答を分岐表示するだけで、別正本を持たない。

template展開とtemplate編集はonline限定で`planning:manage`を要求する。担当者は自分の作業の進捗だけ更新でき、DB triggerが進捗以外の列変更を拒否する。計画管理者は従来どおり作業指示全体を更新できる。

## 検証とrollback

`0014_advanced_planning_verify.sql`はowner／FORCE RLS、template、capacity競合、作付進捗、progress追記専用を検査する。BFF testはtemplate 2 stepの展開、依存、進捗、同一作業リストへの反映を検査する。

`0014_advanced_planning_rollback.sql`はtemplateまたはprogress eventが存在する場合に停止する。空の場合だけview・表・追加列を除去し、作業指示の更新policyを0013時点へ戻す。
