# macOS Production runbook

対象は`infra/hosts/macos/`のnative実装で、`local-integration`、Docker Desktop、対話ログイン中のterminal processをProduction runtimeに使わない。2台以上の管理対象Macを別failure domainとして用意し、同じ署名済みpkgをlaunchd system daemonとして配備する。

1. `sw_vers`、`uname -m`、`fdesetup status`、`pmset -g custom`、`softwareupdate --list`を証跡化する。FileVault無効、sleep有効、support対象外OSなら停止する。
2. 専用の非ログインservice account、`/Library/Application Support/ISAS/Production`、`/Library/Logs/ISAS/Production`、`/Library/LaunchDaemons`を署名済みinstallerで作る。利用者home、`~/.docker`、`infra/local` volumeを参照しない。
3. launchd plistは`RunAtLoad`、`KeepAlive`、resource limit、stdout／stderr先を固定し、secret値をplistへ書かない。Keychainまたは承認済み外部secret adapterから短寿命に取得する。
4. edgeだけを外部公開し、Application Firewall＋pfでDB、IdP、object／queue、telemetryの管理portを拒否する。自動login、画面共有の常時許可、個人Apple IDを禁止する。
5. `sudo launchctl bootstrap system <plist>`、`kickstart -k system/<label>`、`kill SIGTERM`によるstart／restart／graceful stopをStagingで実行する。
6. OS更新は片系ずつdrain→backup確認→update→reboot→readiness→traffic復帰する。2台同時更新、強制sleep、無監視の自動major upgradeを禁止する。
7. APFS snapshotだけをbackupにせず、PostgreSQL整合backup／WAL、object、監査、鍵参照を暗号化off-host recovery setへ保存し、代替Macへ全損restoreする。
8. acceptance schemaへ2 failure domain、sleep／update／disk full、launchd自動復旧、backup／restore、RLS／失効／監査／SLO E2Eを登録する。

```sh
sudo env \
  ISAS_SUPPORTED_MACOS_MAJORS="REPLACE_WITH_APPROVED_MAJOR_LIST" \
  ISAS_ARTIFACT_DIR=/secure/isas/artifacts \
  ISAS_PF_INTERFACE=en0 \
  sh infra/hosts/macos/bin/install.sh

sudo launchctl print system/com.isas.edge
sudo launchctl kickstart -k system/com.isas.edge
sudo launchctl kill SIGTERM system/com.isas.edge

node ops/host-profiles/check-host-profile.mjs infra/hosts/macos/profile.json /secure/evidence/macos-acceptance.json
```

backupは暗号化済みoff-host領域と、WAL本体・各inventory・監査anchor・秘密値を含まない鍵参照を指定する。identityとobject／queueは署名済みpkgに含まれるexport commandでrecovery setへ退避する。

```sh
sudo env \
  ISAS_RECOVERY_DIR=/Volumes/EncryptedOffHost/isas \
  ISAS_RECOVERY_ENCRYPTION_VERIFIED=YES \
  ISAS_WAL_ARCHIVE_DIR=/secure/isas/wal \
  ISAS_WAL_INVENTORY=/secure/isas/evidence/wal-inventory.json \
  ISAS_OBJECT_INVENTORY=/secure/isas/evidence/object-inventory.json \
  ISAS_AUDIT_ANCHOR=/secure/isas/evidence/audit-anchor.json \
  ISAS_KEY_REFERENCE=/secure/isas/evidence/key-reference.json \
  /usr/local/libexec/isas-production-backup
```

更新は待機系のreadinessを先に確認し、片系だけをdrain・backup・停止して署名済みpkgを適用する。同じ操作を2台同時に実行しない。

```sh
sudo env \
  ISAS_NODE_ID=macos-prod-a \
  ISAS_PEER_READY_URL=https://macos-prod-b.example/health/ready \
  ISAS_ARTIFACT_DIR=/secure/isas/artifacts \
  ISAS_RECOVERY_DIR=/Volumes/EncryptedOffHost/isas \
  ISAS_RECOVERY_ENCRYPTION_VERIFIED=YES \
  ISAS_WAL_ARCHIVE_DIR=/secure/isas/wal \
  ISAS_WAL_INVENTORY=/secure/isas/evidence/wal-inventory.json \
  ISAS_OBJECT_INVENTORY=/secure/isas/evidence/object-inventory.json \
  ISAS_AUDIT_ANCHOR=/secure/isas/evidence/audit-anchor.json \
  ISAS_KEY_REFERENCE=/secure/isas/evidence/key-reference.json \
  /usr/local/libexec/isas-production-rolling-update
```

全損復旧は代替Macへ同じversionのpkgを導入後、空のdatabase data directoryと空のWAL restore directoryを用意して実行する。hash検証が失敗したrecovery setは使わない。

```sh
sudo env \
  ISAS_RECOVERY_SET=/Volumes/EncryptedOffHost/isas/REPLACE_WITH_RECOVERY_SET_ID \
  ISAS_RESTORE_WAL_DIR=/Library/Application\ Support/ISAS/Production/data/wal-restore \
  /usr/local/libexec/isas-production-restore
```

native実装と副作用のないOS分岐・構文・plist検査の完了によりKCOMP-H3の実装処置は完了とする。実Mac 2台で全acceptance gateが0終了し二人承認が揃うまでは、`macos-production`およびProduction releaseを引き続き`BLOCKED`とする。
