# ADR-0009：認証・セッション＝OIDC＋BFF Cookie／WebAuthn優先MFA／サーバ権限導出

| 項目 | 内容 |
|---|---|
| ステータス | **採用（再クローズ） v4**（v3のBFF主体／DB注入境界を、公開HTTP入力→context再導出→DB正規化→`SET LOCAL`→RLSの単一経路として確定。S8はPostgreSQL 16.4で12群PASS、BFFアダプタは集合拡張拒否とROLLBACK失敗時の接続破棄を検証済み。第4回で残存 **High 0／Medium 0**。[レビュー記録](レビュー記録_ADR-0009.md)） |
| 日付 | 2026-08-14 |
| 由来 | 要裁定（要求仕様5.4「MFAを選択可」、7章「認証基盤」。PWA・RLS・複数法域を成立させる具体方式を確定する） |
| 関連 | [要求仕様5.4](../../農業営農支援システム_要求仕様書.md)、[ADR-0001 RLS](ADR-0001-マルチテナント分離-行レベル-RLS.md)、[ADR-0002 法域・シャード](ADR-0002-配備モデル-1DB-1国.md)、[ADR-0005 RBAC](ADR-0005-権限モデル-RBAC-メンバーシップ.md)、[ADR-0006 PWA](ADR-0006-フロントエンド構成-React-PWA.md)、[ADR-0007 同期](ADR-0007-オフライン同期方式.md)、[ADR-0008 API](ADR-0008-API方式.md)、[ADR-0017 セキュリティ] |

---

## 1. 背景・課題（Context）

本システムの認証は、単なるログイン方式ではなく、次の境界を同時に閉じる必要がある。

- React PWAはオフライン運用するが、ブラウザに長寿命Bearer/refresh tokenを置くとXSS・端末紛失時の被害が大きい。
- 認証で本人を確定した後、法域→シャード→テナント→ロール／圃場スコープを解決し、毎DBトランザクションへRLS文脈を注入する必要がある。**認証（誰か）と認可（何ができるか）を混同してはならない**。
- 1ユーザーは複数テナントに所属し得るが、通常業務リクエストの `allowed_tenants` は所属全体ではなく、選択中テナント等の**リクエスト単位の許可集合**である（ADR-0005）。
- 数日圏外の現場ではオンラインセッション失効後も最小限の記録を続けたい。一方、解雇・権限剥奪をオフライン端末へ即時反映することは原理的にできない。
- 複数法域入口は資格情報や権限を中央集約せず、法域ルーティングだけを最小化して扱う必要がある（ADR-0002）。
- 外部公開APIは人間のセッションと異なるサービス主体を持ち、本人起点横断を付与してはならない（ADR-0008）。

参照する現行標準は [OpenID Connect Core 1.0 Errata 2](https://openid.net/specs/openid-connect-core-1_0-errata2.html)、[OAuth 2.0 Security BCP（RFC 9700）](https://www.rfc-editor.org/rfc/rfc9700.html)、[WebAuthn Level 2](https://www.w3.org/TR/webauthn-2/)、[NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html) とする。

## 2. 決定（Decision）

### 2.1 人間ユーザーの主体認証

- **標準OIDCを採用**し、フローは **Authorization Code＋PKCE（S256）**とする。Implicit Grant、Resource Owner Password Credentials Grant、URLへトークンを載せる方式は採用しない。
- PWAはOIDC public clientとしてトークンを直接扱わず、**同一オリジンのBFF（Backend for Frontend）をconfidential client**とする。BFFが認可コード交換・refresh token保持・上流APIへのBearer付与を担う。
- issuerは配備設定のallowlist、redirect URIは完全一致、`state`・OIDC `nonce`・PKCE verifierをログイン試行へ一回限りで束縛する。ID Tokenは署名、`iss`、`aud`、`exp`、`nonce`、必要なら`azp`を検証する。
- アプリ内ユーザーの不変キーはメールアドレスでなく **`(issuer, subject)` → internal `user_id`（UUID）**。メール・表示名は変更可能属性であり、アカウント結合キーにしない。異なるissuer間の名寄せは自動化せず、監査付き管理手続きとする。
- 未登録の `(issuer, subject)` を、同じメールアドレスの既存ユーザーへ**自動結合しない**。初回provisioningは、(a) 管理者が発行した期限付き・単回の招待（tenantと初期roleの上限を束縛）、または (b) 運用者が明示したJIT規則、のどちらかに限る。招待対象メールを使う場合もIdPの`email_verified=true`を確認するが、**メールは招待到達確認であって永続IDではない**。管理者・グループ管理者のJIT昇格は禁止する。
- アプリ本体にパスワード検証器を実装しない。運用者は標準OIDC IdP（自己運用またはマネージド）を選べる。OSSとして特定ベンダー固有claimへ依存しない。

### 2.2 ブラウザセッションとトークン保持

- ブラウザへ渡すのはランダムな不透明セッションIDだけとし、`__Host-isas_session` Cookieへ **`Secure; HttpOnly; SameSite=Lax; Path=/`、`Domain`なし**で格納する。access/refresh/ID tokenをLocalStorage、SessionStorage、IndexedDB、Service Worker cacheへ保存しない。
- BFF側はセッションIDのハッシュ、OIDC token set、`user_id`、認証時刻、認証強度をサーバ側ストアへ保持する。token setは保存時暗号化し、ログへ出さない。**単一の可変な「選択中tenant」はセッションへ持たない**（別タブのtenant切替が既存タブへ波及するため）。tenant文脈は§2.5のcontext IDでリクエストごとに束縛する。
- オンラインセッションは**アイドル12時間、絶対7日**を既定とし、IdP側の有効期間が短ければそちらを優先する。認証成功・権限昇格時はセッションIDをローテーションする。refresh tokenはIdPが対応するrotation/reuse detectionまたはsender constraintを有効にし、未対応IdPではBFFの絶対期限を短縮する。
- 状態変更APIは**GET/HEADで実装しない**。SameSiteだけに依存せず、CSRF token、`Origin`/`Sec-Fetch-Site`検証、JSON＋custom headerを併用する。CORSは配備allowlistの明示オリジンだけを許可し、credential付きワイルドカードを禁止する。
- ログアウトはBFFセッションを即時失効し、可能ならIdP logout／refresh token失効も行う。別端末の全セッション失効、IdP back-channel logout／失効通知は対応可能なIdPで接続する。

### 2.3 MFA・step-up・回復

- 要求の「MFAを選択可」は**運用者がポリシーを強化できる**意味とする。既定は、**グループ管理者・組織管理者・権限管理者はMFA必須**、一般作業員は選択可。機微操作はロールを問わずstep-upを要求する。
- MFAの優先順位は **WebAuthn/passkey（フィッシング耐性）→ TOTP → 単回復旧コード**。SMS・メールOTPは通常の第2要素として採用せず、通知または本人確認済み回復手続きの補助に限る。
- IdPごとに、検証済みの `acr`／`amr` 値をアプリの認証強度へ写像するallowlistを配備設定する。未知値やclaim欠落をMFA済みと扱わない。所属解決後、選択tenantのrole/policyが現在の強度を上回る場合は、OIDC `max_age`／`acr_values`等で再認証し、戻ったID Tokenの`auth_time`と強度を再検証する。
- 機微操作は権限／組織変更、MFA・回復手段変更、原価／経営情報、位置軌跡・労務、エクスポート、外部クライアント発行、監査設定とする。step-upの認証鮮度は**15分以内**を既定とし、操作側が要求する認証強度をBFFセッションで確認する。
- 復旧コードは一度だけ表示し、ハッシュ化・単回使用とする。認証器追加・削除、アカウント回復、管理者リセットは監査し、既存の強い認証器または運用者の本人確認手続きを要求する。**管理者本人による自分のMFA解除は禁止**し、管理者アカウントの回復は別の権限者による承認または運用者service deskの本人確認を要求する。回復・認証器変更は既存の通知先へ通知し、回復直後の権限変更／外部client発行には再step-upを要求する。
- 共有アカウントを禁止し、共有端末でも個人ごとにログインする。現場の切替時間はpasskey等で短縮し、監査actorを曖昧にしない。

### 2.4 主体認証後の所属解決

1. BFFがOIDCを検証し、`(issuer, subject)` から内部 `user_id` を確定する。
2. **未認証の任意user探索は禁止**する。主体確定後だけ、法域内の認証専用P1プールから各シャードのallowlist済みブートストラップ関数を並列実行する。
3. `auth_role` はテーブル権限ゼロ、関数`EXECUTE`だけを持つ。関数は `bootstrap_owner` 所有、`SECURITY DEFINER`、固定`search_path`、入力・返却型固定とし、シャード集合／リクエスト主体のメンバーシップ候補だけを返す（ADR-0001/0002）。業務行は返さない。
4. 結果は短TTL（**既定60秒、上限5分**）でキャッシュし、加入・脱退・ロール／スコープ変更イベントで両方向に即時無効化する。停止シャードを含む結果は`partial`として扱い、完全な権威判断に使わない。
5. ログイン探索は主体・送信元ごとにレート制限と同時実行上限を設け、通常業務・P0同期と接続プールを分離する。存在有無を外部エラー文へ出さない。

IdP採用時は、PKCE/nonce、署名アルゴリズム、鍵ローテーション、`acr`/`amr`、WebAuthn、refresh rotation/reuse detection、logout／失効通知の相互運用マトリクスを契約テストする。未対応機能を「対応済み」とみなさず、上記の期限短縮または運用制約へ落とす。

複数法域入口を置く場合、中央IDは不透明ID→法域集合だけを扱い、氏名、資格情報、tenant、role、scopeを保持しない。単一法域URLのログインは中央IDを経由しない。中央障害時は複数法域入口の新規解決だけを止め、法域別入口と既存セッションは継続する。

### 2.5 リクエストごとの認可文脈とRLS注入

OIDC claimやBFFセッションへ業務権限集合を焼き込まない。tenant選択時、BFFは現在権限を確認して**不透明・短TTLのcontext ID**を発行する。context IDは **BFF session ID**、`user_id`、法域、単一シャード、選択tenant、用途（通常／グループ横断／本人横断）へサーバ側で束縛し、ブラウザはタブ／画面単位で保持して各リクエストへ付ける。tenant切替は既存contextの意味を書き換えず、新しいcontext IDを発行する。別sessionのcontext ID、期限切れ、用途違いは拒否する。context ID自体は権限の真実源ではなく、各リクエストで現在権限から次の **AuthContext** を再導出する。

| 値 | 意味・制約 |
|---|---|
| `app.user_id` | 認証済みリクエスト主体。対象者IDへ流用しない |
| `app.tenant_id` | 書込先／通常リクエストの選択中tenant |
| `app.allowed_tenants` | リクエスト単位の許可集合。通常・受託者は原則1要素、グループ横断時だけ検証済み配下集合 |
| `app.scope_field_groups` | 当該tenantで許可された圃場グループ集合 |
| `app.caps` | 当該tenantのrole/permissionから導出した操作capabilityの部分集合 |
| `app.employer_subject_users` | 双方向確認済み雇用関係から導出した、労務サマリ横断対象だけの集合 |

- クライアントがtenant/scope/capabilityを自己申告しても**候補**としてしか扱わない。サーバ導出結果に存在しない値は拒否する。
- 公開HTTPから受け取る `user_id`、actor、認証強度ヘッダは**削除・無視**し、主体は検証済みBFF sessionからだけ取得する。内部APIへ渡す主体envelopeは入口層が生成し、外部から同名ヘッダを注入できない経路／mTLS等で保護する。
- 1つのAuthContextへ異なるシャードのtenantを混在させない。クロスシャードreadはADR-0008に従い、入口層がシャードごとに別contextを導出・注入し、RLS通過結果だけを集約する。
- 注入集合には配備上限を設ける。上限超のtenant/scope/user集合をGUCへ詰め込まず、ページ化・シャード別分割またはcompleteファンアウトへ切り替える。重複、空要素、不正UUID、上限超過はDB検証前に拒否し、DB側でも再検査する。
- 全DBアクセスは明示トランザクションとし、最初にDB側検証関数でAuthContextの包含・組合せを1回検証してから `set_config(..., true)` 相当で `SET LOCAL` する。同じトランザクション内でのみ業務SQLを実行する。注入漏れ・空文字は正規化されRLSで0行となる。
- **DB側検証の保証範囲を限定する**：DBは「渡された `user_id` に対してtenant/scope/capability集合が正当か」を検証するが、OIDC本人性そのものは検証しない。`HTTP主体 → user_id` の束縛はBFFの責務であり、共有app roleを使う以上、入口層の完全侵害はRLSだけでは防げない。したがってuser IDをリクエスト値から採らないこと、入口→内部APIの主体envelope、セッションID／相関IDとの監査接続を必須にし、この信頼境界をADR-0017で独立に脅威評価する。
- 読取集合と書込先を分け、`WITH CHECK`は常に `app.tenant_id` を基準にする。グループ横断readの `allowed_tenants` を書込権限へ流用しない。
- 機微操作は権限キャッシュを使わず権威DBで都度検証する。一般操作は60秒キャッシュを許すが、変更イベントで即時無効化する。
- 認証強度・step-upはアプリ層で操作可否を守り、RLSは行可視性・capability・tenant/scopeを最終防波堤として守る。どちらか一方だけに責務を寄せない。

### 2.5.1 【v4】tenant／scope注入の単一経路

tenant／scopeの注入は、次の順序以外を禁止する。各段はfail closedとし、検証失敗時に前段の値へフォールバックしない。

| 境界 | 受け入れる値 | 生成／検証 | 禁止事項 |
|---|---|---|---|
| ブラウザ→BFF | `__Host-isas_session` Cookie、`X-ISAS-Context`、context発行時の単一`tenantId` | Cookieとcontext IDのハッシュ参照、session束縛、TTL、用途を検証 | `user_id`、role、scope、capability、`allowed_tenants`、actor、認証強度の公開HTTP申告を使わない |
| BFF内部 | sessionから得た`user_id`とcontextの`tenantId`／用途 | 認証専用経路の現在membership/role/scopeからAuthContext候補を**毎リクエスト再導出** | context発行時の古いscope/capabilityを再利用しない |
| BFF→業務コー | 信頼済みAuthContextオブジェクト | 同一プロセス内はメモリ上で受け渡す。別プロセス化する場合は短寿命・audience束縛・リプレイ防止付き内部envelope＋mTLSとする | 公開入口と同名の主体ヘッダを素通ししない。エッジで同名外部ヘッダを削除する |
| 業務コア→PostgreSQL | 正規化したUUID/capability集合とactor仮名ID | 非特権`app_user`で明示transactionを開き、`app_private.validate_auth_context(...)`に候補を渡す | 生SQLから`SET`／`set_config`／transaction制御を実行させない |
| PostgreSQL検証→RLS | DBが拒否または縮小した正規集合 | 主体と書込tenantの一致、各集合がBFF候補の部分集合であることを再確認し、`set_config(..., true)`でtransaction-local GUCへ注入 | DBは候補を**拡張しない**。主体／書込tenantを置換しない |
| 業務SQL→終了 | RLS通過行のみ | 業務SQLと監査を同一transactionで実行しCOMMIT/ROLLBACK | ROLLBACKに失敗した接続をプールへ戻さない |

context用途は形を固定する。Phase 1の`tenant`用途は`allowed_tenants=[tenant_id]`、`employer_subject_users=[]`とする。将来の`group_read`、`self_labor_read`、`employer_labor_read`は別の発行API／必要capability／集合上限を持ち、汎用の「任意集合context」は作らない。権限・scope・membership変更時はセッションを必ずしも切断せず、関連contextとOffline Authorization Snapshotを即時無効化し、次リクエストの再導出で新権限へ収束させる。

S8はPostgreSQL 16.4で通常tenant、scope、capability、group横断、雇用主横断、失効、空／重複集合、権限基表の直接参照拒否、関数所有者／`search_path`／非委譲を含む12群をPASSした。BFFアダプタは上記の部分集合条件と接続破棄を自動テストする。

### 2.6 オフライン認証継続

オンラインセッションと、オフライン継続資格を分離する。端末へ渡す**署名付きOffline Authorization Snapshot**は、`user_id`、法域、tenant、許可する最小offline capability/scope、membership version、発行・失効時刻、認証時刻、端末installation ID、鍵世代、snapshot IDを持つ。

- Snapshotは**端末がローカル操作を段階縮退させるための証明**であり、サーバAPIのBearer tokenではない。オンライン復帰後にSnapshotだけでAPIを呼ばせず、再認証／現在権限の再評価を必須とする。
- 署名公開鍵はアプリへ同梱／安全に更新し、Snapshot改ざんを拒否する。PWAのinstallation IDは複製耐性を保証しないため「強い端末束縛」とは呼ばない。ネイティブではOSキーストアの非抽出鍵へ束縛できる（ADR-0017）。
- Snapshotの期限判定は端末の壁時計だけに依存しない。最後に検証したサーバ時刻＋連続した単調時計を使い、再起動・スリープ等で連続性を証明できない場合は**より厳しい段階へ縮退**して再認証を促す。時計を戻して猶予を延長できないことをS7で検証する。
- 段階縮退の既定は、**発行後24時間まで＝最小運用データのローカル読書き、24〜72時間＝キャッシュ読取と下書き保持のみ、72時間超＝運用ロック（再認証・未同期送信導線だけ）**。運用者は通信事情に応じて短縮でき、延長上限は14日。72時間超への延長はリスク受容を設定・監査し、機微操作は期間にかかわらず不可。
- 最小offline capabilityは打刻、作業日誌、農薬記録、在庫イベント等の現場記録に限定する。権限変更、管理、原価、個人データ、エクスポート、管理者裁定、外部連携はオンラインstep-up必須。
- 復帰時はまずセッションを再確立し、P0の権限失効を取得する。未同期イベントはSnapshot ID/versionと作成時刻を添えて送るが、サーバが**現在権限と作成時点証跡を再検証**して受理・保留・拒否を決める。失効後の法定記録は捨てず管理者回復キューへ送る（ADR-0007）。
- 明示ログアウト／失効／猶予超過ではローカル利用をロックし、同期済みキャッシュをパージする。未同期データは消さず、再認証後の送信または管理者回復まで暗号化保持して件数を表示する。

### 2.7 外部公開APIのサービス主体

- machine-to-machineはOAuth **Client Credentials**を使うconfidential clientとし、`private_key_jwt`またはmTLSを優先する。共有APIキー単独、password grant、人間ユーザーのrefresh token流用は採用しない。
- access tokenは短寿命（既定5分）、audience・issuer・client ID・単一tenant・allowlist済みscopeを検証する。client credentialsにrefresh tokenを発行しない。
- 外部主体は `actor_type=service`＋`client_id` で監査し、`app.user_id`／本人起点横断／`app.employer_subject_users`を付与しない。外部トークンはP2、単一tenantに閉じ、サーバ側の現在設定からRLS文脈へ厳格写像する。
- client secret／鍵の発行、ローテーション、失効を監査し、平文secretは作成時一度だけ表示する。保管・HSM/KMSはADR-0017/0019で具体化する。

### 2.8 監査・エラー・プライバシー

- ログイン成功／失敗、MFA登録・削除・回復、step-up、セッション発行・失効、tenant切替、所属導出のpartial、外部client変更を監査する。token、Cookie、認可コード、PKCE verifier、TOTP seed、回復コード、PIIをログへ出さない。
- 外部エラーは「資格情報または所属を確認できない」へ統一し、メール／user／tenant／法域の存在を列挙させない。相関IDは非PIIとし、法域内ログだけへ詳細を残す。
- 不審な反復、MFA疲労攻撃、回復失敗、複数法域／シャード探索の増加をレート制限と監視へ接続する（ADR-0017/0020）。

## 3. 検討した選択肢（Options）

| 選択肢 | 評価 |
|---|---|
| **OIDC＋BFF Cookie（採用）** | 標準IdPを交換可能にし、PWAからBearer/refresh tokenを隔離できる。BFFセッションストアの運用負荷は増える |
| SPAがAuthorization Code＋PKCEを直接実行 | 標準上は可能だが、tokenがブラウザJS実行環境へ露出し、オフラインIndexedDBとの混同・XSS時の持出し面が増えるため不採用 |
| アプリ内ID/パスワード | OSS運用は容易に見えるが、パスワード保護、MFA、回復、連携の責務をアプリへ抱える。標準IdP差替え性を失うため既定不採用 |
| 長寿命JWTへtenant/role/scopeを内包 | DB参照を減らせるが、剥奪が期限まで効かず、複数tenantの権限を過剰に焼き込むため不採用 |
| サーバセッションだけで長期オフラインを兼用 | 圏外では検証不能。サーバBearerを端末へ長期保存する危険もあるため、ローカル用署名Snapshotへ分離 |
| SMSを主要MFA | 広く使えるがフィッシング／SIM交換耐性が弱い。WebAuthn優先、TOTP fallbackとする |

## 4. 影響・帰結（Consequences）

**良い影響**

- ブラウザに長寿命tokenを残さず、XSS・端末紛失時の持出し範囲を縮小できる。
- 認証と認可を分離し、OIDC claim／Cookie／offline snapshotがRLS権限の第二の真実源になることを防ぐ。
- 管理者はフィッシング耐性MFAを標準にでき、一般作業員は現場導入性を保てる。
- オフライン継続と即時失効の二律背反を、期限・段階縮退・復帰時再検証として明示できる。

**悪い影響・制約**

- BFF、セッションストア、IdP、認証専用プールが可用性部品として増える。冗長化と鍵管理はADR-0019が必要。
- WebAuthn未対応端末／共有端末の登録・回復運用が必要。TOTP fallbackはフィッシング耐性が下がる。
- PWAではSnapshotの強い端末束縛と安全な保存時暗号化を保証できない。高機微＋長期オフラインはネイティブ経路を推奨する。
- 権限変更は一般操作で最大キャッシュTTLぶん遅れ得る。機微操作は都度検証し、失効イベントを必須とする。

## 5. 実装・検証への引き継ぎ

- BFF/IdP製品の選定とHA、Cookie暗号鍵・token暗号鍵、セッションストア、back-channel logoutの配備はADR-0019/0017。
- WebAuthn共有端末、passkey同期可否、TOTP回復、管理者回復の運用テストはADR-0017/0021。
- AuthContext導出／DB検証関数の参照DDLはS8で12群PASS、BFFトランザクションアダプタも実装済み。残りは版管理・索引・backfill・失効イベントを含む本番migrationへの昇格と、`group_read`／労務横断contextの専用発行API実装。
- オフラインSnapshotの署名検証、24h/72h/14d境界、時計ずれ、logout、失効後の未同期回復をS7へ追加する。
- ログインp95 2秒、全シャードpartial、キャッシュ無効化、認証P1プール枯渇をADR-0020の統合負荷試験で測る。
