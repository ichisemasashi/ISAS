# ISAS BFF core

ADR-0009の同一オリジンBFF境界と、ADR-0007/0008のMVP REST・同期面を実装したコアである。Production runtimeはNode.js HTTP server、graceful shutdown、health probe、`pg` driverによる優先度別poolに加え、Cognito OIDC、KMS envelope暗号化、DynamoDB session/context、SQS失効consumerを`runtime-adapters/aws.mjs`で実接続する。

## Production runtimeの操作

Node.js 22以上を使い、最初に依存を固定lockから導入する。

```bash
cd apps/bff
npm ci
```

必要な環境変数を設定した後、構成だけを検査する。成功時はsecretを含まないJSON logを出して終了code 0、失敗時は終了code 78となる。

```bash
npm run check:config
```

container／ECSでは前景起動を使う。`SIGTERM`または`SIGINT`でreadinessを即時503へ落とし、新規業務requestを拒否して、既定15秒まで処理中requestをdrainした後、adapter、5 poolの順に終了する。`SIGHUP`は同じ安全終了後に終了code 75を返すため、再起動はECS等のsupervisorに委ねる。

```bash
npm start
```

supervisorのない保守端末では、PIDを照合してからsignalする次のcommandを使う。logとPIDは既定で`apps/bff/runtime/`に作られる。停止が時間切れになっても強制終了しないため、当番者が処理中requestとDBを確認してescalateする。

```bash
npm run service:start
npm run service:status
npm run service:restart
npm run service:stop
```

監視endpointは、process生存だけを返す`GET /health/live`と、5 poolおよびadapter依存先を検査する`GET /health/ready`である。ALB互換aliasとして`GET /healthz`もreadinessを返す。livenessをtraffic受入判定に使わない。

### 起動時に必須の構成・secret

| 分類 | 環境変数 | 条件 |
|---|---|---|
| 配備 | `NODE_ENV`、`ISAS_DEPLOYMENT_ID`、`ISAS_JURISDICTION`、`AWS_REGION` | productionは`production`、`isas-jp-prod-NN`、`JP`、`ap-northeast-1` |
| HTTP/OIDC | `ISAS_PUBLIC_ORIGIN`、任意の`ISAS_OIDC_REDIRECT_URI` | HTTPS exact origin。redirectは同originの`/api/bff/callback`だけ |
| adapter | `ISAS_RUNTIME_ADAPTER_MODULE` | local module。`createRuntimeAdapters`と全必須methodを起動前に検査 |
| DB | `ISAS_DB_{P0,AUTH_P1,P1,P2,OPS}_{HOST,PORT,NAME,USER,PASSWORD,SSLMODE}` | productionは5つの異なるPgBouncer endpoint、TLS必須。P1は`app_user` |
| Cognito | `COGNITO_USER_POOL_ID`、`COGNITO_CLIENT_ID`、`COGNITO_ISSUER`、`COGNITO_MANAGED_LOGIN_ORIGIN` | 東京pool、public code-flow client、allowlist済みHTTPS issuer／managed-login origin |
| 永続store | `SESSION_TABLE`、`AUTHORIZATION_REVOCATION_QUEUE_URL`、`TOKEN_SESSION_KMS_KEY_ARN` | DynamoDBの`user-index`、SQS DLQ、single-region KMS keyが起動時read-backで有効 |
| 認証DB secret | `ACTOR_PSEUDONYM_KEY` | 32 byte以上のランダム値。Secrets Managerから注入し、log／planへ出さない |
| 写真 | `ATTACHMENT_ACCESS_POINT_ARN`、`ATTACHMENT_DOWNLOAD_TTL_SECONDS` | VPC-only S3 access point。参照URLは30〜300秒（配備値60秒） |
| オフライン地図 | `OFFLINE_MAP_BUCKET`、`OFFLINE_MAP_ARCHIVE_KEY`、`OFFLINE_MAP_TILESET_VERSION`、`OFFLINE_MAP_ARCHIVE_SHA256` | private PMTilesのMIME、version、ODbL、SHA-256 metadataを起動時に照合。容量／保持は`OFFLINE_MAP_INSTALLATION_LIMIT_BYTES`、`OFFLINE_MAP_PACK_RETENTION_DAYS` |

DBは各classについて上記componentの代わりに`ISAS_DB_<CLASS>_URL`も指定できる。ただしURLをlogや手順書へ貼らない。既定pool上限はP0=8、Auth-P1=12、P1=16、P2=8、Ops=4、statement timeoutは順に3/5/15/30/60秒である。共通調整値は`ISAS_DB_CONNECTION_TIMEOUT_MS`、`ISAS_DB_IDLE_TIMEOUT_MS`、HTTP調整値は`ISAS_REQUEST_TIMEOUT_MS`、`ISAS_HEADERS_TIMEOUT_MS`、`ISAS_KEEP_ALIVE_TIMEOUT_MS`、`ISAS_DRAIN_TIMEOUT_MS`、`ISAS_BODY_LIMIT_BYTES`、`ISAS_READINESS_CACHE_MS`を使う。body上限は最大16MiBで、既定11MiB（10MiB写真とmultipart等の余裕）である。

## 実装済みの境界

- `GET /api/bff/login`：単回state、nonce、PKCE S256を生成し、同一オリジンの`return_to`だけを保持する。`step_up=1`では現在主体を固定し、`prompt=login`と`max_age=0`で再認証する。
- `GET /api/bff/callback`：ログイン試行を単回消費し、検証済みOIDC主体から内部userを解決して、不透明sessionを発行する。
- `GET /api/bff/session`：HttpOnly Cookieから利用者と現在の所属候補だけを返す。OIDC tokenは返さない。
- `POST /api/bff/contexts`：Origin、CSRF、JSONを検証し、現在権限から短TTLのタブ用contextを発行する。
- `POST /api/bff/logout`：DynamoDB session/contextを先に失効し、Cognito refresh token revokeとglobal sign-outを行う。SQS停止時は暗号化済みtoken jobをDynamoDBへ耐久退避し、応答headerのmanaged-login logout URLへWebを遷移させる。
- `createContextResolver`：Cookieと`X-ISAS-Context`の束縛、期限、用途、現在権限を再検証し、業務API／DBアダプタだけが使う信頼済みAuthContextを返す。外部HTTPのuser／role／capabilityヘッダは入力に使わない。
- `createPostgresAuthContextAdapter`：`app_user`が非所有者・非superuser・非BYPASSRLSであることを確認し、`app_private.validate_auth_context(...)`が返した正規値だけを同一トランザクション内のGUCへ注入する。DBはBFFが提示したtenant／scope／capability集合を拒否または縮小できるが、主体・書込tenantの置換や集合の拡張はできない。業務コールバックには`SET`、`set_config`、transaction制御を許さない制限付きclientを渡し、ROLLBACK失敗時は接続を破棄する。
- `createIsasApplication`：上記BFFと実pool向けPostgreSQL repositoryを同一オリジンへ組み込み、認証で再導出した正規AuthContextだけをRESTへ渡す。
- アプリケーションrouterは全API応答へ`nosniff`、frame拒否、API用CSP、same-origin resource policy、referrer／権限制限を付与する。JSONは用途別の上限内で読み込み、写真は宣言MIMEだけでなく先頭signature、UUID、撮影日時、安全なファイル名を検証する。

## MVP REST・同期API

- `GET /api/v1/today`：RLS適用後の当日作業を返す。Webは成功時にIndexedDB cacheを更新する。
- `GET /api/v1/fields`：担当圃場をGeoJSONで返す。`bbox`はPostGIS `&& ST_MakeEnvelope`、`q`は圃場名前方検索、`cursor`はUUIDv7ページングに使う。S2の結論に従いSQLへtenant等値を明示する。
- `GET /api/v1/offline-map-pack`：RLSで担当field-groupを検査し、PostGISで担当圃場bbox＋2km、zoom 8〜16、assignment／tileset version、250MiB上限、期限を返す。`GET /api/v1/offline-map-archive`は単一4MiB以下のPMTiles Rangeだけを受け、各Rangeでsession、AuthContext、RLS割当を再検証する。S3 URLを端末へ公開しない。
- `GET/POST /api/v1/work-instructions`：担当者は割当済み指示を参照し、`instruction:manage`を持つ管理者は指示を発行する。Webの今日画面、14日ガント、モバイル作業リストはこの同じ応答を使い、予定開始・終了と選択状態を連動する。
- `PATCH /api/v1/work-instructions/:id/assignment`：再割当はオフライン同期へ載せず、オンライン専用の`expectedVersion`＋行ロックで競合を409にする。
- `GET /api/v1/journal-bootstrap`：指示、当日打刻からの開始／終了候補、欠落警告、テンプレート、前回値または訂正対象を返す。Webはテンプレート／前回値をIndexedDBへ保存する。
- `POST /api/v1/journal-attachments`：日誌受理後に、端末保存済みのJPEG/PNG/WebP/HEIC（10MB以下）を冪等アップロードする。同じattachment IDへ異なる内容を送った場合は409とする。
- `GET /api/v1/journal-attachments/:id/access`：RLSで添付参照権限を検査し、MIMEと安全なdownload名を固定した最大5分のS3署名URLを返す。object keyは返さない。`POST /api/v1/security-admin/attachment-storage/reconcile`はrecent MFA＋`security:manage`でDB／S3を照合し、未完uploadを確定／隔離して孤立objectへ回収tagを付ける。
- `GET /api/v1/journals`／`POST /api/v1/journals/:id/review`：本人の日誌または管理者のレビュー対象を返し、理由必須の差し戻し／承認を楽観ロック付きで行う。差し戻し・訂正・承認は`journal_revision`へ追記し、承認済み日誌の通常更新は拒否する。
- `GET /api/v1/pesticide-bootstrap`：担当圃場の農薬マスタrelease（version／有効期限／最終同期）、失効日、適用作物、希釈範囲、使用上限、収穫前日数、当年使用履歴、在庫を返す。端末は圃場単位で保存し、オンライン時に強制更新する。
- `POST /api/v1/pesticide-master/releases`：`pesticide:manage`を持つ管理者だけが、楽観版確認付きで鮮度期限のあるマスタreleaseを公開する。
- `GET /api/v1/inventory`：追記型`stock_event`から導出した残高と、管理者向けマイナス在庫アラートを返す。入庫・出庫・棚卸し調整は`stock`同期イベントとして受理し、調整には`inventory:adjust`を再検証する。
- `GET /api/v1/planning/templates`／`POST /api/v1/planning/templates/:id/expand`：作期・作付計画へ日offset付きtemplateを展開し、作業指示・担当・依存・resource割当を同じtransactionで生成する。
- `PATCH /api/v1/work-instructions/:id/progress`：担当者の進捗を追記event＋楽観lockで更新する。ガントとモバイル作業リストは同じ作業指示投影から進捗・依存・resource競合を表示する。
- `POST /api/v1/inventory/purchase-orders`／`POST /api/v1/inventory/receipts`：発注・入荷予定とlot／期限／単価を管理し、入荷数量は追記型stock eventへ記録する。
- `POST /api/v1/inventory/counts`／`POST /api/v1/inventory/counts/:id/post`：server残との差異を棚卸しsessionへ保存し、別管理者の確定でadjustment eventを生成する。
- `GET /api/v1/exports/jgap-inventory.csv`：lot、期限、仕入先、入出庫、数量、評価情報をRLS範囲内で出力する。
- `POST/GET /api/v1/migration-jobs`：`migration:manage`を持つ管理者が圃場・作業記録・農薬履歴CSVの列をマッピングし、ファイル内／DB内の重複と行エラーを業務表へ書き込む前に検査する。
- `POST /api/v1/migration-jobs/:id/commit`：検査済みジョブだけを楽観ロック付きで確定する。確定時にも同時登録との重複を再検査し、取込元ジョブと行番号を保持する。
- `GET /api/v1/exports/{fields,journals,pesticide-records}.csv`：`export:read`とRLS適用後の圃場台帳・作業日誌・農薬記録をUTF-8 CSVで返す。日誌と農薬記録は`from`／`to`日付を指定できる。
- `POST /api/v1/sync/push`：tenant内の依存束を原子受理し、`(tenant_id,event_uuid)`受理台帳で再送を排除する。現在のcapability、membership version、authorization snapshotを受理時に再検証する。
- `GET /api/v1/sync/pull`：`(tenant,scope,priority)`別cursorと取得開始時の上限を使う。P0と通常差分は独立cursorである。
- `GET /api/v1/sync/queues`：権限変更による差し戻しと、フィールド単位の楽観競合を返す。
- `POST /api/v1/sync/conflicts/:id/resolve`：`conflict:resolve`を持つ現在の管理者だけがサーバ値／端末値を裁定し、状態と変更ログを同一transactionで更新する。

Webのoutboxは受理または重複応答を得るまで削除しない。差し戻しと競合は別のIndexedDB queueへ隔離し、オンライン復帰時はP0 pull→push→通常pullの順に処理する。

## PostgreSQL migration

MVP業務表は次の順で適用する。`0000_auth_context_v1.sql`がuser、membership、role、scope、単調`authorization_version`、永続失効event、監査triggerと`validate_auth_context`の正式な起点である。`spikes/S8_auth_context.sql`はこのmigrationと検証SQLを呼ぶだけで、別DDLを持たない。

```bash
psql "$DATABASE_URL" -f apps/bff/migrations/0000_auth_context_v1.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0001_mvp_sync.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0002_conflict_fields.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0003_field_gis.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0004_work_management.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0005_journal_capture.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0006_journal_review.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0007_pesticide_inventory.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0008_data_migration.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0009_field_bbox_prefilter.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0010_identity_runtime.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0011_security_administration.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0012_attachment_object_storage.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0013_phase2_data_model.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0014_advanced_planning.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0015_inventory_traceability.sql
```

旧データを移す場合は、法域内の隔離環境で`backfill/0000_auth_context_v1_stage.sql`を適用し、review済みCSVを`migration_stage`へ`\copy`してから`backfill/0000_auth_context_v1_backfill.sql`を実行する。backfillは全対象userのversionを進めて失効eventを作り、完了時にstaging schemaを削除する。`rollback/0000_auth_context_v1_rollback.sql`は業務表も永続userもない場合だけ成功し、それ以外はdropせず停止する。

業務表はすべて`ENABLE/FORCE ROW LEVEL SECURITY`である。tenant policyをpermissive基底、field scopeと競合裁定capabilityをrestrictive条件にしてAND合成する。アプリ接続は必ず`app_user`を使う。

ローカル検証はnative runnerで再現する。runnerは一時clusterだけを使用し、`00_common.sql`を本番DBへ適用しない。

```bash
ISAS_PG16_BIN=/opt/homebrew/opt/postgresql@16/bin ./spikes/run-native.sh
```

PostgreSQL 16と同major向けPostGISが前提である。全migration／verify、rollback 0017〜0013、S1／S2／S5／S8、S7の完了後に一時processとdataを自動削除する。

## AWS production adapterの保証条件

- Cognito clientはpublic authorization code flow、PKCE S256、必須scope、token revocationを使う。BFFはJWKSからRS256署名、`iss`、`aud`、`exp`、`token_use`、`nonce`、`at_hash`、`auth_time`を検証し、MFAが`ON`かつpasskey user verificationがMFA要素であることを起動時にread-backする。
- `GetUserAuthFactors`と`GlobalSignOut`はaccess token、`RevokeToken`はrefresh tokenで認可され、IAM policyへ偽の許可を追加しない。application IAM roleにはpool設定read-backと管理者back-channel logoutだけを最小付与する。
- password利用者はTOTP、passkey利用者はuser verificationを必須とする。Cognito ID Tokenから個別方式を断定できないため、sessionの強度は誇張せず`mfa`とし、機微操作は10分以内のstep-upを要求する。
- access／refresh tokenはブラウザへ返さず、KMS `GenerateDataKey(AES_256)`＋AES-256-GCMで暗号化する。AADはdeployment、法域、用途、resource IDへ束縛し、session idle 30分、絶対12時間、context 5分で期限判定する。DynamoDB TTLの非同期削除だけを認可判定に使わない。
- `users.resolve`は`(issuer, subject)`だけを不変キーとして内部userを解決し、メールによる自動結合をしない。
- `authorization.listTenants`と`deriveContext`は認証専用DB経路から現在の所属・role・scope・capabilityを導出する。context IDや過去のsnapshotを権限の真実源にしない。
- session ID、state、context IDはハッシュだけをDynamoDB keyへ使う。単調`authorization_version`のmarkerと`user-index`により、session、context、server offline snapshotを再送安全に削除し、旧versionでの再作成をtransaction guardで拒否する。
- `0010_identity_runtime.sql`は`auth_role`専用のOIDC主体解決、所属・scope導出、失効outbox lease関数を追加する。関数はNOLOGIN／NOBYPASSRLS owner、固定`search_path`、PUBLIC実行権限なしであり、Auth-P1 poolだけが呼ぶ。
- `0011_security_administration.sql`は利用者／membership、期限付きbreak-glass、Privacy requestの二人承認関数と農薬master review表を追加する。Auth-P1関数は自己承認をDBで拒否し、業務側review表はtenant RLSと`pesticide:manage`を強制する。

失効queueが受けるmessageは次の3種類である。成功したmessageだけを削除し、重複は単調versionで冪等処理する。`cognito_backchannel_logout`のproducerは管理者の失効transactionと同じ`authorizationVersion`／`eventId`を渡し、Cognito usernameだけを信頼源にしない。

```json
{"type":"authorization_revoked","eventId":"123","userId":"uuid","authorizationVersion":"8","occurredAt":"2026-08-15T00:00:00Z"}
{"type":"cognito_token_revoke","tokenSetCiphertext":"kms-envelope-wrapper"}
{"type":"cognito_backchannel_logout","eventId":"124","userId":"uuid","username":"cognito-username","authorizationVersion":"9","occurredAt":"2026-08-15T00:01:00Z"}
```

## 検証

```bash
cd apps/bff
npm test
npm run check
```

2026-08-22時点で、native PostgreSQL 16.15＋PostGIS 3.6.4上の全version付きmigration／verify、rollback 0017〜0013、S1／S2／S5／S8、S7 15件がPASSしている。Cognito／DynamoDB／KMS／SQS adapter、法域内OpenTelemetry、CI scan／署名、段階配備は実装済みだが、実AWS stagingへのapplyと受入はcredential・DNS・課金承認待ちである。
