# Phase 2 データモデル拡張

| 項目 | 内容 |
|---|---|
| 対象 | Phase 2.0 作期・作付計画、作業依存、resource、在庫policy、分析event、位置情報同意 |
| 正式migration | `apps/bff/migrations/0013_phase2_data_model.sql` |
| 状態 | 実装済み。PostgreSQL 14.24代替環境で、PG15+専用`security_invoker`指定だけ互換置換してmigration・検証10群・backfill・安全rollbackをPASS。正式なPostgreSQL 16＋PostGIS再実行はDocker daemon復旧後のrelease gate |
| 互換性 | expand-only。既存`work_instruction`にはnullableな`crop_plan_id`だけを追加し、旧BFFのread/writeを維持する |

## 正本と不変条件

| 領域 | 正本 | DBで保証する条件 |
|---|---|---|
| 作期 | `app.growing_season` | ローカル暦日`date`、終了日≧開始日、論理削除、楽観lock用`version` |
| 作付計画 | `app.crop_plan` | 作期・圃場と同一tenant、圃場の`field_group_id`一致、面積>0、作業窓の前後関係 |
| 作業依存 | `app.work_instruction_dependency` | 同一tenant FK、自己依存禁止、再帰検査によるcycle禁止、4種の依存とlag |
| resource | `app.planning_resource`／`app.work_resource_allocation` | resourceと作業指示のtenant一致、割当scope一致、数量>0、終了時刻≧開始時刻 |
| 在庫policy | `app.inventory_policy` | 農薬masterと同一tenant、補充点・目標・安全在庫の大小関係、有効期間、1農薬1 active policy |
| 分析event | `app.analytics_event` | event UUID冪等、server権威の`event_ts`、JSON object、INSERT-only |
| 位置情報同意 | `app.location_consent_event` | 本人だけがgrant可能、privacy管理者はwithdrawだけ代理可能、同意文面SHA-256、INSERT-only |
| 現在同意 | `app.location_consent_current` | `security_invoker=true`で最新eventを導出し、基表RLSを迂回しない |

位置情報同意は位置ログそのものではない。位置ログ収集側は、目的別の最新eventが`granted`で、かつ`expires_at`が未到来であることを保存直前にも検査する。withdraw後は新規収集を止め、既存ログの保持・削除は法域profileとPrivacy workflowで処理する。

## RLSと権限

全9表はowner=`app_owner`、`ENABLE ROW LEVEL SECURITY`＋`FORCE ROW LEVEL SECURITY`である。`tenant_isolation`をpermissive基底とし、scope／capabilityをrestrictive policyでAND合成する。

- 計画更新は`planning:manage`、resource更新は`resource:manage`、在庫policy更新は`inventory:policy:manage`を要求する。
- 分析eventは`analytics:write`で追記し、`analytics:read`かつ許可field-groupだけ参照する。
- 位置同意は本人参照・本人grantを既定とし、`privacy:manage`は他者の参照と代理withdrawだけを許す。
- `app.validate_crop_plan_scope()`と`app.validate_resource_allocation_scope()`はSECURITY DEFINERで全tenant行を照合し、作付／割当の非正規化scope偽装を拒否する。owner用policyはNOLOGINの`app_owner`に限定する。
- 新しいcapabilityは`group_admin`へseedする。request contextの`app.caps`は永続membershipから毎回導出し、クライアント申告を採用しない。

## 監査

8業務表すべてに`z_phase2_change_audit`を付け、INSERT／UPDATE／DELETEの前後像、resource key、actor、時刻を`app.phase2_change_audit`へ記録する。分析eventと位置同意は基表も追記専用であり、履歴の上書き・削除権限を`app_user`へ与えない。監査表はtenant RLSに加え`security:manage`を要求する。

## 適用、backfill、rollback

通常配備ではmigration runnerが`0013_phase2_data_model.sql`をchecksum付きで適用する。旧作業指示へ作付を関連付ける場合だけ、隔離stagingで次を実行する。関連を推測して自動補完してはならない。

```bash
psql "$DATABASE_URL" -f apps/bff/migrations/backfill/0013_phase2_data_model_stage.sql
psql "$DATABASE_URL" -c "\\copy migration_stage.work_instruction_crop_plan FROM 'reviewed-map.csv' CSV HEADER"
psql "$DATABASE_URL" -f apps/bff/migrations/backfill/0013_phase2_data_model_backfill.sql
```

backfillは、両IDの存在、同一tenant、圃場、field-group、review者・日時を検証し、対応した作業指示だけ`version`を進める。終了後はstaging表を削除する。

rollbackは新表・監査表が全て空、既存作業指示が作付を未参照、独自roleが新capabilityを未使用の場合だけ成功する。1件でも業務データがあればdropせず停止し、backup restoreまたはroll-forwardを選ぶ。

```bash
psql "$DATABASE_URL" -f apps/bff/migrations/rollback/0013_phase2_data_model_rollback.sql
```

## 検証

`verify/0013_phase2_data_model_verify.sql`はowner／FORCE RLS、監査trigger、作期・作付、cycle拒否、resource、在庫policy、server時刻、本人同意、RLS拒否、追記専用を検査する。N/N-1は、0013適用後に旧BFF testを再実行し、nullable列追加で従来契約が変わらないことを確認する。

2026-08-16の実装時はBFF 86/86、migration検証10/10、空DB rollback、review mapping backfill、データありrollback拒否がPASSした。Docker engine socketが無応答だったため、PostgreSQL 16固有の正式再実行だけを未完gateとして残す。PostgreSQL 14で未対応の`security_invoker`は既存の0012までと同じPG16契約であり、正式migrationから削除しない。
