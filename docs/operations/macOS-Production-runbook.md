# macOS Production runbook

対象は`infra/hosts/macos/profile.json`で、`local-integration`、Docker Desktop、対話ログイン中のterminal processをProduction runtimeに使わない。2台以上の管理対象Macを別failure domainとして用意し、同じ署名済みbundleをlaunchd system daemonとして配備する。

1. `sw_vers`、`uname -m`、`fdesetup status`、`pmset -g custom`、`softwareupdate --list`を証跡化する。FileVault無効、sleep有効、support対象外OSなら停止する。
2. 専用の非ログインservice account、`/Library/Application Support/ISAS`、`/Library/Logs/ISAS`、`/Library/LaunchDaemons`をMDM／署名済みinstallerで作る。利用者home、`~/.docker`、`infra/local` volumeを参照しない。
3. launchd plistは`RunAtLoad`、`KeepAlive`、resource limit、stdout／stderr先を固定し、secret値をplistへ書かない。Keychainまたは承認済み外部secret adapterから短寿命に取得する。
4. edgeだけを外部公開し、Application Firewall＋pfでDB、IdP、object／queue、telemetryの管理portを拒否する。自動login、画面共有の常時許可、個人Apple IDを禁止する。
5. `sudo launchctl bootstrap system <plist>`、`kickstart -k system/<label>`、`kill SIGTERM`によるstart／restart／graceful stopをStagingで実行する。
6. OS更新は片系ずつdrain→backup確認→update→reboot→readiness→traffic復帰する。2台同時更新、強制sleep、無監視の自動major upgradeを禁止する。
7. APFS snapshotだけをbackupにせず、PostgreSQL整合backup／WAL、object、監査、鍵参照を暗号化off-host recovery setへ保存し、代替Macへ全損restoreする。
8. acceptance schemaへ2 failure domain、sleep／update／disk full、launchd自動復旧、backup／restore、RLS／失効／監査／SLO E2Eを登録する。

```sh
node ops/host-profiles/check-host-profile.mjs infra/hosts/macos/profile.json /secure/evidence/macos-acceptance.json
```

0終了と二人承認が揃うまでKCOMP-H3は未処置、Productionは`BLOCKED`とする。
