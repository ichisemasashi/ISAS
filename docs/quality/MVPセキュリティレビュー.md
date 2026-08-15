# MVPセキュリティレビュー

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-15 |
| 対象 | Web MVP、同一origin BFF、REST／同期API、PostgreSQL AuthContext／RLS経路 |
| 基準 | ADR-0009、ADR-0017、OWASPの一般的なWeb脅威分類 |
| 判定 | **実装コアはPASS、実配備は条件付き（下記阻害条件あり）** |

## 実施内容と結果

| 観点 | 結果 | 証拠／判断 |
|---|---|---|
| 認証情報のブラウザ隔離 | PASS | ブラウザは`__Host-` HttpOnly Cookieとopaque contextだけを使用し、Bearer／refresh tokenをWeb APIへ公開しない。state、nonce、PKCE、issuer側検証契約を定義済み |
| CSRF／origin | PASS | 全変更APIでexact Origin、同一site Fetch Metadata、timing-safe CSRF比較を実施 |
| tenant／scope注入 | PASS | sessionに束縛したcontextを毎回再導出し、DB側`validate_auth_context`が集合の拡張を拒否。非owner・非superuser・非BYPASSRLSをtransaction内で検査 |
| SQL injection | PASS | repositoryの外部値はparameter bindingを使用。業務callbackからtransaction制御、`SET`、`set_config`を禁止 |
| 入力サイズ／添付偽装 | **是正後PASS** | 一般JSONへ256KiB上限を追加。添付は10MiB上限に加えJPEG/PNG/WebP/HEIC signature、UUID、撮影日時、ファイル名を検証 |
| XSS／clickjacking／MIME sniff | **API境界は是正後PASS** | 全API応答へ`default-src 'none'`／`frame-ancestors 'none'`、`DENY`、`nosniff`、same-origin resource policy、referrer／permissions policyを付与。Reactに危険なHTML sinkは検出されなかった |
| CSV formula injection | PASS | 出力セル先頭の式文字をneutralizeする既存テストあり |
| Service Worker | PASS | `/api/`、Authorization付き応答、private/no-store、Set-Cookie応答をcacheしない。更新はoutbox空のときだけ適用 |
| secret／tokenの混入 | PASS（追跡ファイル） | 秘密鍵、主要provider token形式、コード内password代入を追跡ファイルから検索し該当なし。OIDC token setは暗号文のみstoreへ渡す契約 |
| 本番依存脆弱性 | PASS | `pnpm audit --prod --audit-level high`：既知脆弱性0件（2026-08-15照会） |
| 回帰試験 | PASS | BFF 50件、構文checkがPASS |

## 今回の是正

- API routerで一貫した防御headerを付与し、router外の個別handlerによる付け忘れを防止した。
- 汎用JSON読込を無制限の`request.json()`からbyte上限付き読込へ変更し、競合解決を含む全対象経路へ適用した。
- 添付の`Content-Type`自己申告だけを信用せず、画像signatureとmetadataを受理前に検証した。
- 偽装JPEGと過大JSONを拒否する回帰試験を追加した。

## リリース阻害条件

以下は設計済みだが、現在のrepositoryには本番adapterまたは配備構成がない。解消するまで「本番セキュリティPASS」とはしない。

1. 実IdPでのID Token署名／issuer／audience／nonce／`azp`／認証強度検証、MFA・step-up・回復フロー。
2. 暗号化済みtoken setを扱う永続session/context store、単調version付き失効配信、rate limit。
3. TLS ingress、信頼proxyの限定、SPA静的配信の厳格CSP（nonce/hashとTrusted Types）、HSTS、成果物署名／SBOM。
4. PostgreSQL pool driverとS8 AuthContext参照DDLの本番migration化、secret manager／KMS接続。
5. 添付の隔離object storage、マルウェアscan／安全な再encode、期限付きdownload、輸出step-up／監査／自動削除。
6. ADR-0017 S9の端末暗号化、暗号消去、offline recovery wrap、鍵交代／復旧試験。ブラウザPWAを高機微・長期offline用途へ無条件に適用しない。
7. 構造化security log、異常検知、失効dead-letter、監査ハッシュchain検証の運用監視。

## 残留リスク

- API用CSPは実装したが、SPAの実効CSPは静的ホスト／ingressがHTMLへ付与して初めて成立する。
- 画像signature確認はMIME偽装の一次防御であり、malwareや画像parser脆弱性を排除しない。公開前scan／再encodeが必要である。
- 依存監査は照会時点の既知情報に限られる。CIでproduction依存、container、IaC、secret、SBOMを継続検査する。
- PWAのIndexedDBは強い保存時暗号化・遠隔消去を保証しない。ADR-0017の制約どおり、端末分類でネイティブ経路へ切り替える。
