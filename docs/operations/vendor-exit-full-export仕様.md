# vendor exit full export仕様（KCOMP-M8）

CSV単体では完了としない。1 tenantを停止時点へ固定し、次のdatasetを同一snapshotから出力する。

- tenant、user、membership、role、scope、失効event。
- 圃場geometry、作期・作付、依存・resource、作業指示、打刻、日誌、訂正・差し戻し。
- 農薬master参照版、使用記録、在庫event、lot、棚卸、調整。
- 位置同意・撤回・保持期限・閲覧監査。
- 写真その他の添付本体とmetadata、業務監査event、監査hash chain。

top-level manifestにはexport schema version、source release／migration、tenant、snapshot時刻、各fileのmedia type・record count・byte count・SHA-256、attachment object key mapping、第三者dataの除外理由を記録する。別の空ISASへimportし、件数、hash、参照整合、RLS範囲、添付download、監査chainを検証する。原配備の削除はrestore検証と二人承認後だけ行い、対象、日時、消去方式、backup失効予定、legal hold、実行者・確認者を削除証明へ残す。

現状はこの完全export／import経路と実restore証跡がないため`未処置`である。部分CSVをvendor exit完了と表示しない。
