# FreeBSD Production implementation

このdirectoryはADR-0019 v4のFreeBSD Jail profileを実装する。Docker、Compose、OCI runtime、Linux guestを使わない。

- `manifest.json`: 6 service boundary、固定Jail名、address、dataset、quota、resource上限。
- `config/jail.conf`: VNET Jail、mount／raw socket拒否、epair lifecycle。
- `config/pf.isas.conf`: Jail間default denyと必要portだけの許可。
- `config/rctl.conf`: Jail別memory／process上限。
- `rc.d/isas`: databaseから順に起動し、edgeから順にgraceful停止するhost service。
- `bin/install.sh`: FreeBSD／root検査、Jail別ZFS・secret dataset、署名検証済みnative pkgの導入。
- `bin/backup.sh`／`restore.sh`: PostgreSQL base backupを含むZFS stream、WAL／object／監査／鍵参照のrecovery setとhash検証。

各`<service>.pkg`はFreeBSD native pkgとして、そのserviceの実行file、`/usr/local/etc/rc.d/` service、既定offの設定sample、SBOM参照を含める。秘密値をpkgへ含めない。導入時はservice別pkgと`.sig`、検証用公開鍵を安全なartifact領域へ置く。

OS分岐だけを副作用なしで確認するには次を実行する。

```sh
for os in FreeBSD Darwin Linux; do
  ISAS_HOST_OS="$os" ISAS_DISPATCH_ONLY=1 sh ops/host-profiles/install-host.sh
done
node ops/host-profiles/check-host-profile.mjs infra/hosts/freebsd/profile.json
```

実FreeBSDでのinstall、backup／restore、E2Eは[FreeBSD Production runbook](../../../docs/operations/FreeBSD-Production-runbook.md)に従い別途受入する。静的検査PASSをProduction受入済みと解釈しない。
