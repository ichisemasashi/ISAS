# Linux Production runbook

対象は`infra/hosts/linux/`のnative実装で、初期support matrixはDebian 13 stableまたはUbuntu 24.04 LTSのamd64／arm64とする。point releaseとsecurity supportを配備時に公式情報で再確認する。ISASはDocker／OCI daemonを使わず、専用system userとsystemd sandboxで境界を分離する。

1. empty hostで`cat /etc/os-release`、`uname -m`、`systemd --version`、`nft list ruleset`、`lsblk -f`、`systemctl is-active systemd-timesyncd`を証跡化する。definition外OS、LUKS2なし、cgroup v2なしなら停止する。
2. 専用system user、read-only署名済みartifact、`/etc/isas`、`/var/lib/isas`、分離した`journald@isas` namespaceを宣言構成で作る。secretをEnvironmentFile、package、unitへ平文保存せず、`systemd-creds`で暗号化する。
3. systemd unitに`NoNewPrivileges=yes`、`PrivateTmp=yes`、`ProtectSystem=strict`、`ProtectHome=yes`、空の`CapabilityBoundingSet=`、限定`ReadWritePaths`、restart上限を設定する。ISAS runtimeにDocker／containerd socketを渡さない。
4. nftablesはdefault denyとし、edgeへのTLS、service間の明示port、管理CIDRだけを許可する。DB／IdP／telemetry管理portを外部公開しない。
5. database（PostgreSQL／PgBouncer）→identity／object-queue→app→edge→telemetryをsystemd dependencyで起動し、`systemctl start isas.target`、`systemctl stop isas.target`、`systemctl restart isas-app.service`のgraceful drainを測る。
6. security updateは片系ずつdrainし、保存済みpackage／artifactでrollback可能にする。unattended rebootで全failure domainを同時停止しない。
7. PostgreSQL base backup＋継続WAL、object inventory、監査anchor、鍵参照を暗号化off-host recovery setへ保存し、empty hostへrestoreする。
8. acceptance schemaへinstall、hardening、reboot、upgrade／rollback、disk full、backup／PITR／全損restore、RLS／失効／監査／SLO E2Eを登録する。

```sh
sudo env ISAS_PROVISION_STORAGE=NO sh infra/hosts/linux/bin/bootstrap.sh

# 次の操作は署名のない空block deviceをLUKS2で初期化する。device名を再確認し、
# backup対象deviceやmount中deviceには絶対に実行しない。
sudo env \
  ISAS_PROVISION_STORAGE=YES \
  ISAS_EMPTY_DATA_DEVICE=/dev/REPLACE_WITH_EMPTY_DEVICE \
  ISAS_LUKS_KEY_FILE=/secure/isas/luks.key \
  ISAS_CONFIRM_LUKS_FORMAT=YES \
  sh infra/hosts/linux/bin/bootstrap.sh

for service in database identity object-queue app edge telemetry; do
  sudo systemd-creds encrypt --name=config \
    "/secure/isas/credentials/$service.conf" \
    "/etc/isas/credentials/$service.cred"
done

sudo env \
  ISAS_DATA_MOUNT=/var/lib/isas \
  ISAS_LUKS_DEVICE=/dev/REPLACE_WITH_LUKS_SOURCE_DEVICE \
  ISAS_LUKS_MAPPER=/dev/mapper/isas-data \
  ISAS_UPS_MODE=datacenter-backed \
  ISAS_UPS_EVIDENCE=/secure/isas/evidence/power.json \
  ISAS_SUPPORT_MATRIX_EVIDENCE=/secure/isas/evidence/os-support.json \
  ISAS_ARTIFACT_DIR=/secure/isas/artifacts/REPLACE_WITH_VERSION \
  ISAS_ARTIFACT_REGISTRY=REPLACE_WITH_REGISTRY_ID \
  ISAS_RELEASE_VERSION=REPLACE_WITH_IMMUTABLE_VERSION \
  ISAS_SIGNING_PUBLIC_KEY=/secure/isas/signing-public.pem \
  ISAS_MANAGEMENT_CIDR="REPLACE_WITH_MANAGEMENT_CIDR" \
  sh infra/hosts/linux/bin/install.sh

sudo systemctl status isas.target
sudo systemctl restart isas-app.service
sudo systemctl stop isas.target
sudo systemctl start isas.target

node ops/host-profiles/check-host-profile.mjs infra/hosts/linux/profile.json /secure/evidence/linux-acceptance.json
```

backup、rolling update、rollback、empty-host restoreのentrypointは、それぞれ`/usr/local/libexec/isas-production-{backup,rolling-update,rollback,restore}`である。必須環境変数とrecovery set構造は`infra/hosts/linux/bin/`の各scriptを正本とし、2台同時更新を禁止する。

ユーザー指定により、今回のKCOMP-H4実装処置はLinuxを含むOS分岐と静的構成の確認を完了条件とする。実Linuxで全acceptance gateが0終了し二人承認が揃うまでは、`linux-production`およびProduction releaseを引き続き`BLOCKED`とする。
