# FreeBSD Production runbook

対象は`infra/hosts/freebsd/profile.json`で、Docker／Compose／Linux guestを使用しない。FreeBSD 15.1-RELEASEまたは14.4-RELEASEのJailを正規方式とする。releaseのsupport期限は配備時にFreeBSD Security Informationで再確認する。

1. `freebsd-version -ku`、`uname -m`、`zpool status`、`service pf status`、`rctl -h`を証跡へ保存する。definition外のversion、暗号化されていないdata disk、pf無効なら停止する。
2. `zfs create -o mountpoint=/jails/isas zroot/jails/isas`の下に`edge`、`app`、`database`、`identity`、`object-queue`、`telemetry`のdatasetとquotaを作る。DB／object／backup datasetを共有しない。
3. review済み`pkg` repositoryと署名済みapplication bundleだけを各Jailへ導入する。`pkg audit -F`に未処置High/Criticalがあれば起動しない。
4. `jail.conf`でVNETを有効化し、epairとpf tableを使って`edge`以外への外部着信を拒否する。host filesystem全体、raw device、host root、`allow.mount`をapp Jailへ渡さない。
5. rc.d serviceをdatabase→pool→identity／object-queue→app→edge→telemetryの順に起動する。`service jail start isas_database`等の各終了codeと`/health/ready`を記録する。
6. 通常停止はedge drain→app／worker→poolの順とし、DB／WAL／objectを同時削除しない。`service jail stop <name>`後もZFS datasetと未配送outboxを保持する。
7. `zfs snapshot`だけをbackup成功にしない。PostgreSQL base backup＋継続WAL、object inventory、監査anchor、鍵参照を同じrecovery setへ記録し、暗号化したoff-host媒体へ複製する。
8. `ops/host-profiles/acceptance.example.json`をGit外へ複製し、reboot、upgrade／rollback、Jail停止／侵害、PITR、空hostへの全損restore、RLS／失効／監査／SLO E2Eの実証URIを登録する。

```sh
node ops/host-profiles/check-host-profile.mjs infra/hosts/freebsd/profile.json /secure/evidence/freebsd-acceptance.json
```

実装の静的検査は、受入証跡を渡さずに同commandを実行して0終了することで確認する。実FreeBSD上の二人承認付き受入が揃うまで、profileとProductionは`BLOCKED`のままとする。
