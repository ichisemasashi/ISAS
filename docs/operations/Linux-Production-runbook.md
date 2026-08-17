# Linux Production runbook

対象は`infra/hosts/linux/profile.json`で、初期support matrixはDebian 13 stableまたはUbuntu 24.04 LTSのamd64／arm64とする。point releaseとsecurity supportを配備時に公式情報で再確認する。

1. empty hostで`cat /etc/os-release`、`uname -m`、`systemd --version`、`nft list ruleset`、`lsblk -f`、`systemctl is-active systemd-timesyncd`を証跡化する。definition外OS、LUKS2なし、cgroup v2なしなら停止する。
2. 専用system user、read-only署名済みartifact、`/etc/isas`、`/var/lib/isas`、`/var/log/isas`を宣言構成で作る。secretをEnvironmentFile、image、unitへ平文保存しない。
3. systemd unitに`NoNewPrivileges=yes`、`PrivateTmp=yes`、`ProtectSystem=strict`、`ProtectHome=yes`、`CapabilityBoundingSet=`、限定`ReadWritePaths`、restart上限を設定する。rootless OCIを使う場合もDocker socketをmountしない。
4. nftablesはdefault denyとし、edgeへのTLS、service間の明示port、管理CIDRだけを許可する。DB／IdP／telemetry管理portを外部公開しない。
5. database→pool→identity／object-queue→app→edge→telemetryをsystemd dependencyで起動し、`systemctl start isas.target`、`systemctl stop isas.target`、`systemctl restart isas-app.service`のgraceful drainを測る。
6. security updateは片系ずつdrainし、保存済みpackage／artifactでrollback可能にする。unattended rebootで全failure domainを同時停止しない。
7. PostgreSQL base backup＋継続WAL、object inventory、監査anchor、鍵参照を暗号化off-host recovery setへ保存し、empty hostへrestoreする。
8. acceptance schemaへinstall、hardening、reboot、upgrade／rollback、disk full、backup／PITR／全損restore、RLS／失効／監査／SLO E2Eを登録する。

```sh
node ops/host-profiles/check-host-profile.mjs infra/hosts/linux/profile.json /secure/evidence/linux-acceptance.json
```

0終了と二人承認が揃うまでKCOMP-H4は未処置、Productionは`BLOCKED`とする。
