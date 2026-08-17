# Production host profiles

macOS、Linux、FreeBSDは同格のProduction host対象である。各definitionは共通validatorで静的検査し、対象OSのStagingで作成したacceptance evidenceと組み合わせて初めて承認できる。

```bash
node ops/host-profiles/check-host-profile.mjs infra/hosts/freebsd/profile.json
node ops/host-profiles/check-host-profile.mjs infra/hosts/freebsd/profile.json /secure/evidence/freebsd.json
```

| Profile | Runtime | Runbook | 状態 |
|---|---|---|---|
| [`freebsd/profile.json`](freebsd/profile.json) | Jail＋rc.d＋VNET／pf＋ZFS＋rctl | [FreeBSD](../../docs/operations/FreeBSD-Production-runbook.md) | definition実装済み、実機受入未実施 |
| [`macos/profile.json`](macos/profile.json) | native service＋launchd | [macOS](../../docs/operations/macOS-Production-runbook.md) | definition実装済み、2 host受入未実施 |
| [`linux/profile.json`](linux/profile.json) | native／OCI＋systemd＋nftables | [Linux](../../docs/operations/Linux-Production-runbook.md) | definition実装済み、empty-host受入未実施 |

exampleの`status=BLOCKED`を手で`READY`にしない。acceptance evidenceの全gateが`PASS`で、二人の異なる承認者を持つ場合だけvalidatorが0終了する。
