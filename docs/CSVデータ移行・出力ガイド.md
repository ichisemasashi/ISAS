# CSVデータ移行・出力ガイド

## 1. 対象と権限

管理画面の「その他」から、圃場・作業記録・農薬履歴をCSVで取り込み、作業日誌・圃場台帳・農薬記録をCSVで出力できる。取込には`migration:manage`、出力には`export:read`が必要であり、どちらもオンライン時だけ実行する。DBのRLSは常に有効で、選択中tenantと現在の閲覧範囲を越えるデータは出力しない。

## 2. CSV取込

### 2.1 共通手順

1. 「その他」→「CSVデータ取込」で対象とCSVファイルを選ぶ。
2. 検出されたヘッダーを、ISASの項目へ割り当てる。列順と元の列名は自由である。
3. 「重複と入力内容を検査」を実行する。この時点では業務データを変更しない。
4. 取込候補・重複・エラー件数と、指摘された行を確認する。
5. エラーがあれば元CSVを修正して新しいジョブとして再検査する。検査済みになった場合だけ「取込を確定」を実行する。

CSVはUTF-8（BOMあり／なし）、ヘッダー1行、最大50,000データ行、最大200列とする。引用符で囲んだ改行・カンマと、引用符の二重化に対応する。空行は読み飛ばす。

### 2.2 圃場

| ISAS項目 | 必須 | 形式 |
|---|---:|---|
| `externalKey` | ○ | 移行元で一意な圃場コード |
| `name` | ○ | 圃場名 |
| `fieldGroupId` | ○ | ISASの圃場グループUUID |
| `geometryWkt` | ○ | SRID 4326の`POLYGON(...)`または`MULTIPOLYGON(...)` |
| `cropName` |  | 作物名 |
| `timezone` |  | IANA timezone。空欄は`Asia/Tokyo` |

同じファイル内または同じtenantの既存圃場に同一`externalKey`があれば重複として取り込まない。

### 2.3 作業記録

| ISAS項目 | 必須 | 形式 |
|---|---:|---|
| `externalKey` | ○ | 移行元で一意な記録コード |
| `fieldExternalKey` | ○ | 先に取り込んだ圃場コード |
| `workerUserId` | ○ | ISAS利用者UUID |
| `workType` | ○ | 作業種別 |
| `workedOn` | ○ | `YYYY-MM-DD` |
| `startedAt` / `endedAt` | ○ | `HH:MM`。終了は開始以降 |
| `memo` |  | 作業メモ |

同一`externalKey`は重複とする。参照する圃場コードが存在しない行はエラーになる。移行済み日誌は履歴データとして`approved`で登録する。

### 2.4 農薬履歴

| ISAS項目 | 必須 | 形式 |
|---|---:|---|
| `fieldExternalKey` | ○ | 先に取り込んだ圃場コード |
| `cropName` | ○ | 作物名 |
| `registrationNumber` | ○ | 先に登録した農薬マスタの登録番号 |
| `usageCount` | ○ | 0以上の整数 |
| `lastAppliedOn` | ○ | 最終使用日`YYYY-MM-DD` |

圃場、作物、農薬、最終使用日の年を単位に初年度の使用回数と最終使用日を登録する。同じ組合せの既存集計は重複とし、圃場または農薬マスタを解決できない行はエラーになる。詳細な法定散布記録を捏造せず、オフライン安全判定に必要な初期集計として保持する。

### 2.5 確定と再実行

取込作成APIは`Idempotency-Key`とファイルSHA-256で再送を判定する。同じキーと同じ内容の再送は同じジョブを返し、同じキーで異なる内容は拒否する。確定はジョブ`version`による楽観ロックを行う。検査後に別処理が同じキーを登録した場合も、確定時に再度重複として除外する。

## 3. CSV出力

「その他」→「CSVデータ出力」で対象を選ぶ。作業日誌と農薬記録には任意の開始日・終了日を指定でき、両端を含む。圃場台帳は現在有効な圃場をすべて出力する。

| 出力 | 主な列 |
|---|---|
| 作業日誌 | 記録コード、圃場コード・名、作業者ID、作業日、作業種別、開始・終了、メモ、状態、提出日時 |
| 圃場台帳 | 圃場コード・名、作物、状態、面積㎡、タイムゾーン、境界WKT |
| 農薬記録 | 散布日、圃場、作物、登録番号・薬剤名、希釈倍率、散布量、対象病害虫、作業者、使用器具、収穫予定日、サーバ判定 |

ファイルはExcelでも文字化けしにくいUTF-8 BOM付きである。値の先頭が`=`, `+`, `-`, `@`の場合は、表計算ソフトで数式として実行されないよう先頭へ`'`を付ける。1回の上限は100,000行であり、超える場合は期間を分割する。

## 4. API

- `POST /api/v1/migration-jobs`：CSV、対象、マッピングを検査用ジョブへ保存する。`Idempotency-Key`必須。
- `GET /api/v1/migration-jobs`：直近100ジョブを返す。
- `POST /api/v1/migration-jobs/:id/commit`：`expectedVersion`を指定して検査済みジョブを確定する。
- `GET /api/v1/exports/fields.csv`
- `GET /api/v1/exports/journals.csv?from=YYYY-MM-DD&to=YYYY-MM-DD`
- `GET /api/v1/exports/pesticide-records.csv?from=YYYY-MM-DD&to=YYYY-MM-DD`

書込APIには同一オリジンCookie、`X-ISAS-Context`、CSRF tokenが必要である。外部スクリプトから直接呼ぶ場合も、ブラウザ管理画面と同じBFF認証境界を迂回してはならない。

## 5. 実データrehearsal

本番移行前に、個人情報を除去または仮名化した実CSVを隔離したAWS staging tenantへ投入する。具体的な環境変数、manifest、実行commandは[`ops/data-migration/README.md`](../ops/data-migration/README.md)を正とする。

1. rehearsal専用tenantを初期化し、移行担当者とfield-group制限付き照合担当者を作る。RLSの縮小を実証するため、制限担当者の範囲外となる圃場を最低1件含める。
2. 移行元責任者が、元CSVの件数、想定重複、想定エラー、確定予定件数をmanifestへ記入する。
3. `run-rehearsal.py`で圃場→作業記録→農薬履歴を順に検査・確定する。同一key再送、件数恒等式、確定時重複も自動検査される。
4. 全scopeと制限scopeで3種類のCSVを出力し、manifestの件数と照合する。制限scopeの件数は全scope以下で、少なくとも1種類は真に少なくなければならない。
5. 移行元責任者と、実行者とは別の照合者が原本・画面・監査記録を確認し、証跡へ承認を追記する。
6. `node ops/data-migration/check-rehearsal.mjs /secure/rehearsal/evidence.json`が`PASS`になることを本番移行の必要条件とする。

圃場・作業記録は、隔離tenantであれば確定件数と対応する出力行を照合できる。農薬履歴取込はオフライン安全判定用の初期使用集計であり、法定散布明細である「農薬記録」出力を捏造しない。このため、農薬履歴取込件数と農薬記録出力件数は別々の期待値として照合し、同数を要求しない。

実CSV本文、Cookie、CSRF token、氏名・連絡先・実圃場名はGitへ追加しない。Gitに残すのは匿名化した集計、SHA-256、job ID、artifact参照だけとする。
