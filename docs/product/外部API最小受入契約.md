# 外部API最小受入契約（KCOMP-M1）

最初の公開対象は圃場のread-only APIに限定する。browser cookieを外部clientへ流用せず、ADR-0013のservice identity、単一tenant、`fields:read` scope、RLSを使用する。

受入には次の実値が必須である。

- client owner、利用目的、tenant、IdP client ID、issuer、audience、sandbox／production endpoint。
- `GET /api/v1/fields`のOpenAPI contract、page／cursor、version、180日前廃止通知。
- rate上限、`429 Retry-After`、credential失効、security緊急停止と監査結果。
- sandboxでのtenant越境、scope拡張、cursor流用、token失効試験。
- 実clientの名称・version・source digest、契約者、support時間、障害・security窓口。
- client ownerと独立確認者の二人承認、証跡URI。

現状は実client、発行済みservice identity、sandbox、運用窓口の受入がないため`未処置`である。
