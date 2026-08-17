# Linux Production native implementation

ADR-0019 v4のLinux Production profileを、Debian 13またはUbuntu 24.04 LTS上のnative systemd serviceとして実装する。ISAS runtimeはDocker／OCI daemonへ依存しない。

- `manifest.json`: support matrix、amd64／arm64、最低resource、6 service境界、共通SLO。
- `config/`: AppArmor、nftables default deny、system user／directory、分離journald namespace。
- `systemd/`: 起動順、graceful stop、自動復旧、sandbox、暗号化credential、監視／証明書更新timer。
- `bin/preflight.sh`: distribution、architecture、CPU／memory／disk、LUKS2、AppArmor、cgroup v2、NTP、UPSを検査。
- `bin/install.sh`: detached signature、SBOM、provenanceを検証し、version固定debとOS構成をempty hostへ導入。
- `bin/backup.sh`／`restore.sh`: PostgreSQL base backup、WAL、identity、object／queue、監査、鍵参照を含む暗号化off-host recovery set。
- `bin/rolling-update.sh`／`rollback.sh`: 片系drain、backup、version切替、readiness、rollback。

副作用なしの確認:

```sh
ISAS_HOST_OS=Linux ISAS_DISPATCH_ONLY=1 sh ops/host-profiles/install-host.sh
node ops/host-profiles/check-host-profile.mjs infra/hosts/linux/profile.json
find infra/hosts/linux/bin -type f -name '*.sh' -exec sh -n {} \;
```

実Linux上のinstall／restore／E2Eは[Linux Production runbook](../../../docs/operations/Linux-Production-runbook.md)で別途受入する。今回の静的検査とOS分岐確認をProduction承認へ昇格しない。
