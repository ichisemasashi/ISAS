# macOS Production native implementation

ADR-0019 v4のmacOS Production profileを、Docker Desktopや対話login sessionに依存しないnative serviceとして実装する。`local-integration`とはprofile ID、data、secret、log、release artifact、起動管理を共有しない。

- `manifest.json`: 6つのservice境界、非login user、loopback port、共通SLO。
- `launchd/`: `RunAtLoad`／`KeepAlive`付きsystem daemon、pf起動時適用、継続health監視。
- `config/pf.isas.conf`: edgeの443だけを外部公開し、内部portを拒否する。
- `bin/preflight.sh`: OS／architecture、FileVault、sleep、時刻同期、Production専用pathを検査する。
- `bin/install.sh`: signed pkgを検証し、専用user・directory・launchd・pfを配備する。
- `bin/backup.sh`／`restore.sh`: PostgreSQL整合backup、WAL、object、監査、鍵参照を含む暗号化off-host recovery setを作成・検証する。
- `bin/rolling-update.sh`: 片系drain、backup、署名済み更新、再起動、readiness確認を行う。
- `bin/monitor.sh`: liveness／readinessとP0 500ms閾値を検査し、外部監視に読ませる終了codeを返す。

副作用なしの確認:

```sh
ISAS_HOST_OS=Darwin ISAS_DISPATCH_ONLY=1 sh ops/host-profiles/install-host.sh
node ops/host-profiles/check-host-profile.mjs infra/hosts/macos/profile.json
find infra/hosts/macos/bin -type f -name '*.sh' -exec sh -n {} \;
plutil -lint infra/hosts/macos/launchd/*.plist
```

実機2台へのinstall、全損restore、E2E、共通SLOは[macOS Production runbook](../../../docs/operations/macOS-Production-runbook.md)で別途受入する。静的検査PASSはProduction承認ではない。
