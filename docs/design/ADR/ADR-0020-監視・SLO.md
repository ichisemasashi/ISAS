# ADR-0020：監視・SLO＝法域内テレメトリ・エラーバジェット・復旧監視

| 項目 | 内容 |
|---|---|
| ステータス | **採用（クローズ） v2**（v1のSLI/SLOを維持し、ADR-0023のlocal OTel stack、profile label、PII canary、単一Mac測定の証跡限界を波及。波及レビュー残存 **High 0／Medium 0**。[レビュー記録](レビュー記録_ADR-0020.md)／[ADR-0023レビュー](レビュー記録_ADR-0023.md)） |
| 日付 | 2026-08-15 |
| 由来 | 要求仕様5.1／5.2／5.3、ADR-0007の同期優先度、ADR-0008の法域内可観測性、ADR-0019のHA・RPO/RTO |
| 関連 | [ADR-0002 配備モデル](ADR-0002-配備モデル-1DB-1国.md)、[ADR-0003 監査](ADR-0003-データライフサイクル-追記型-論理削除-版履歴-監査.md)、[ADR-0007 同期](ADR-0007-オフライン同期方式.md)、[ADR-0008 API](ADR-0008-API方式.md)、[ADR-0017 セキュリティ](ADR-0017-セキュリティ基盤-端末暗号化-失効-脅威モデル.md)、[ADR-0019 インフラ・運用](ADR-0019-インフラ・運用.md)、[ADR-0021 テスト・リリース](ADR-0021-テスト・リリース方式.md)、[ADR-0023 Mac local integration](ADR-0023-Mac本番相当ローカル統合環境.md) |

---

## 1. 背景・原則

「CPUを監視する」だけでは、利用者が日誌を保存できるか、P0失効が先に届くか、復旧可能なbackupかを説明できない。またtenant/user IDをそのままlabelやtraceへ出すと、可観測性自体がRLS外の高cardinalityな関係graphになる。

そこで、利用者視点のSLI/SLO、原因調査用のtelemetry、法定監査logを分離する。収集・保存・閲覧・通知の全経路を法域内に閉じ、OpenTelemetryのvendor-neutralなsignal model（[仕様](https://opentelemetry.io/docs/specs/otel/)）を採る。SLO alertは単一閾値ではなく、error budgetのmulti-window burn rate（[Google SRE Workbook](https://sre.google/workbook/alerting-on-slos/)）を用いる。

## 2. 決定

### 2.1 Signalと責務の分離

| Signal | 目的 | 保存・制約 |
|---|---|---|
| Metric | SLI、capacity、queue、backup、security件数 | 法域内。低cardinality labelだけ。生tenant/user/field/device IDは禁止 |
| Trace | BFF→core→DB/object/queueの遅延・失敗箇所 | 法域内。W3C trace context相当の非PII ID。payload、SQL bind、Cookie、tokenを記録しない |
| Operational log | 診断、deploy、failover、retry | 構造化allowlist。外部errorには非PII correlation IDだけを返す |
| Security event | login失敗、MFA回復、scope拒否、secret/key操作 | 集約件数と仮名化actor。security担当だけが詳細参照 |
| Audit log | 誰が何を変更・閲覧したか、改ざんchain | ADR-0003の追記型権威データ。一般log基盤へ置換せず、別保持・権限・chain検証 |
| Client RUM | 地図、ガント、offline同期開始、PWA更新 | 明示同意・sample・最小化。自由入力、位置、写真、圃場名、端末fingerprintを送らない |

collector/exporter障害で業務requestを失敗させない。process内bufferを有界にし、満杯時はtelemetryをdropしてdrop件数を法域内で数える。audit/securityの権威eventはこのbest-effort経路に載せず、業務transactionのoutboxから耐久配送する。

### 2.2 SLIの共通定義

- 集計窓は特記なければrolling 30日。release判定ではcandidate環境の固定試験窓も併記し、production実績を置換しない。
- **eligible request**は認証済みの通常利用と合成probe。client中断、構文不正4xx、明示したabuse rate limitは分母外。認証済み正常trafficへのserver-side 429、5xx、timeout、依存障害は失敗に含める。
- availabilityは`good eligible transactions / all eligible transactions`。予定maintenanceも分母に含め、maintenanceを宣言してSLOを良く見せない。
- latencyは成功したeligible transactionのserver受信からresponse完了まで。失敗を除外したlatencyだけで合格させず、同じscopeのavailabilityと対で判定する。
- UI時間はnavigation/interaction開始から利用可能状態までをRUMと再現可能E2Eの両方で測る。APIだけ速くてもUI SLO合格にしない。
- `deployment_id`、`jurisdiction`、`shard_id`、`service`、`route_template`、`priority_class`、`status_class`、`release_digest`だけを基本labelとする。URL実値、tenant/user/resource IDをlabelにしない。

### 2.3 MVP SLO catalog

| Service／操作 | SLI・良好条件 | 目標／窓 |
|---|---|---|
| 管理系online | eligible transaction成功 | **99.5%以上／rolling 30日** |
| P0安全・失効pull | 成功かつserver処理500ms以内。P2飽和時も同じ | **99.9%以上／rolling 30日** |
| 一般GET | 成功応答のserver latency | **p95 500ms以内／rolling 24hと30日** |
| Login | 主体認証後の所属導出を含む完了時間。partialは失敗 | **p95 2秒以内／24hと30日** |
| 圃場一覧 | 10,000件・pagingの利用可能表示 | **p95 1.5秒以内** |
| 地図初期 | 1,000圃場、style/tileを含む利用可能表示 | **p95 2秒以内** |
| 地図操作 | pan/zoom入力から追従frame | **p95 200ms以内** |
| ガント | 500 taskの利用可能表示 | **p95 3秒以内** |
| 日誌保存 | 写真1枚の整合した受理、本文だけの部分成功不可 | **p95 1秒以内** |
| 同期開始 | online回復検知から最初のP0要求開始 | **99%以上が60秒以内／7日** |
| 1日分同期 | 規定fixture（写真含む数十件）の全受理／保全済みquarantine | **95%以上が5分以内／7日** |
| 失効配送 | 権威event commitから全online consumerのversion適用 | **99.9%以上が60秒以内／24h** |
| WAL保護 | 最新archive成功からの経過 | **5分以内を99.9%／30日、15分超は即page** |
| 復旧 | 四半期recovery set演習のdata loss／所要 | **RPO 15分以内、RTO 4時間以内、100%成功** |
| 監査chain | 前日分のchain／anchor検証 | **毎日100%。欠損・不一致0件** |

p95 SLOは試行数30未満ではrelease合格に使わない。サンプル不足を0msや100%として扱わず`no_data`にする。productionのtenant別sliceは法域内の制限された診断queryで行い、常設metric labelへtenantを載せない。

### 2.4 Error budgetとalert

99.5% availabilityの30日error budgetは0.5%、時間換算で約3時間36分である。P0 99.9%は約43分12秒。release、変更凍結、incident優先度は残budgetで決める。

| 条件 | 通知 | 初動 |
|---|---|---|
| 14.4倍burnが1h窓と5m窓の両方 | Sev-1 page | 5分以内ack。進行中deploy停止、直近変更／依存／poolを確認 |
| 6倍burnが6h窓と30m窓の両方 | Sev-2 page | 15分以内ack。容量・長期劣化を担当へ引継ぎ |
| 1倍超が3日継続、または30日budget残25%未満 | ticket＋release freeze | 改善計画とowner/dateが入るまで高risk release禁止 |
| WAL 15分超、監査chain不一致、法域外export検知、P0失効DLQ 60秒超 | Sev-1（比率を待たない） | 安全側へ停止／隔離。平文や古い権限へ縮退しない |

alertは`owner`、runbook URL、dashboard、法域、release digest、severityを必須annotationとする。alert通知本文にPIIを載せず、on-callから法域内consoleへ認証して詳細を見る。自動復旧は冪等で上限回数を持ち、failoverや長期離脱を無限loopで実行しない。

### 2.5 必須dashboardと原因指標

1. **利用者体験**：availability/latency/RUM、route、release、priority別、error budget。
2. **同期・安全**：P0/P1/P2 queue depth/age、outbox受理、idempotency hit、conflict、rejection、quarantine件数／最古年齢、revocation consumer version lag。
3. **DB/shard**：pool待ち、active/idle、transaction/lock、replica/WAL lag、archive age、CPU/memory/storage、slow query fingerprint（値なし）、PostGIS候補件数。
4. **権限・監査**：RLS拒否集約、bootstrap同時実行、session/context失効、audit chain/anchor、owner/trigger/security-invoker drift。
5. **object/PWA**：upload失敗、孤立DB参照／object、署名URL失敗、cache/outbox保存失敗、Service Worker更新保留、同期前purge防止。
6. **DR/deploy**：backup age/size/checksum、recovery set完全性、restore所要、migration version、instance digest、config drift、rollback回数。

weak referenceやobject孤立は日次照合し、壊れた関連を自動削除せず「関連切れ」として表示する。quarantineは件数だけでなく最古年齢を監視し、7日超をSev-2、30日超をrelease blockerにする。

### 2.6 保持・アクセス・品質

- metric 90日、trace 30日、operational/security log 90日を既定とし、法令・契約に応じ短縮／延長する。auditと業務データは各保持policyに従い、監視都合で無期限化しない。
- production observability readは法域別RBAC＋MFA、bulk exportとsecurity detailはstep-up＋監査を必須にする。developerの常時PII read権限を設けない。
- collector、alert rule、dashboard、redaction、samplingはcode review対象の宣言構成とする。secret/PII canaryを非本番で流し、export先に出ないことを自動testする。
- metric cardinality、dropped spans/logs、collector queue、scrape/export失敗、alert deliveryを「監視の監視」として持つ。signal欠落をSLO達成と解釈しない。

### 2.7 【v2】Mac本番相当ローカル統合環境

- localはOpenTelemetry Collector、Prometheus、Jaeger、Persesを採用し、既存BFFのOTLP contractを通す。auditはPostgreSQLの権威経路を維持し、Jaeger／logへ置換しない。
- 全signalへ`deployment_id=local-integration`、`evidence_scope=local-integration`、source／component lock digestを付ける。tenant／user／field IDをlabelにしない既存規則は同じである。
- dashboardは5 poolの待ち／上限、route latency／error、失効queue／DLQ、object pending／orphan、RLS拒否、migration、collector dropを最低表示する。
- synthetic PII／secret canaryがmetric、trace、log、dashboardへ残らないこと、collector停止時に業務が有界dropで継続しdrop件数を回復後観測できることを試験する。
- p95は30試行未満と`no_data`をPASSにしない。ただし単一Macの値は回帰診断であり、rolling production SLO、error budget、capacity、alert運用実績へ算入しない。

## 3. 検討した選択肢

| 選択肢 | 評価 | 結論 |
|---|---|---|
| host CPU/死活だけ | 原因は見えるが利用者の成功、同期鮮度、復旧性を測れない | 不採用 |
| 全tenant/userをmetric label化 | 絞込は容易だがPII、関係graph、cardinality爆発を生む | 禁止。制限queryで診断 |
| logだけを監査にも流用 | 改変・drop・retentionが異なり法定証跡を保証できない | auditを権威経路として分離 |
| 平均latency | tail劣化を隠す | p95＋availabilityを採用 |
| 固定閾値alert | 一瞬のnoiseか長期budget消費か区別しにくい | multi-window burn rateを採用 |
| 全法域を単一SaaS監視へ集約 | 運用は楽だがlog/trace経由の越境になる | 法域別store／accessを採用 |

## 4. 帰結とrelease条件

- 稼働率だけでなくP0、同期、失効、監査、復旧の未観測がrelease blockerになる。
- telemetry費用を抑えるためsampleするが、SLI counter、audit/security権威event、backup/chain検査結果はsampleしない。
- 99.5%は高可用性を意味するが無停止を保証しない。offline継続性は別SLIとして監視する。
- dashboardが緑でも`no_data`、cardinality超過、export失敗があれば合格にしない。
- local dashboardの緑はsignal contractの成立を示すだけで、法域内保持、on-call通知、production trafficのerror budgetを証明しない。

方式判断は完了した。採用製品、法域別保持、on-call名簿、通知provider、予測trafficは配備時入力とし、ADR-0021のrelease manifestに固定する。実ingress／実端末／実networkの測定が揃うまでは、本番releaseを承認しない。
