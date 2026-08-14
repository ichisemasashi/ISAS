import { useEffect, useMemo, useState } from "react";
import type { ExportDataset, MigrationDataset, MigrationJob, MvpGateway } from "./api";

type FieldDefinition = { key: string; label: string; required: boolean };

const DATASETS: Record<MigrationDataset, { label: string; fields: FieldDefinition[] }> = {
  fields: { label: "圃場", fields: [
    { key: "externalKey", label: "圃場コード", required: true }, { key: "name", label: "圃場名", required: true },
    { key: "fieldGroupId", label: "圃場グループID", required: true }, { key: "geometryWkt", label: "区画（WKT）", required: true },
    { key: "cropName", label: "作物", required: false }, { key: "timezone", label: "タイムゾーン", required: false },
  ] },
  journals: { label: "作業記録", fields: [
    { key: "externalKey", label: "記録コード", required: true }, { key: "fieldExternalKey", label: "圃場コード", required: true },
    { key: "workerUserId", label: "作業者ID", required: true }, { key: "workType", label: "作業種別", required: true },
    { key: "workedOn", label: "作業日", required: true }, { key: "startedAt", label: "開始時刻", required: true },
    { key: "endedAt", label: "終了時刻", required: true }, { key: "memo", label: "メモ", required: false },
  ] },
  pesticide_history: { label: "農薬履歴", fields: [
    { key: "fieldExternalKey", label: "圃場コード", required: true }, { key: "cropName", label: "作物", required: true },
    { key: "registrationNumber", label: "農薬登録番号", required: true }, { key: "usageCount", label: "使用回数", required: true },
    { key: "lastAppliedOn", label: "最終使用日", required: true },
  ] },
};

const EXPORTS: Array<{ dataset: ExportDataset; label: string }> = [
  { dataset: "journals", label: "作業日誌" }, { dataset: "fields", label: "圃場台帳" }, { dataset: "pesticide-records", label: "農薬記録" },
];

function firstRecord(csv: string): string[] {
  const values: string[] = [];
  let value = "";
  let quoted = false;
  for (let index = csv.charCodeAt(0) === 0xfeff ? 1 : 0; index < csv.length; index += 1) {
    const character = csv[index];
    if (character === '"') {
      if (quoted && csv[index + 1] === '"') { value += '"'; index += 1; } else quoted = !quoted;
    } else if (character === "," && !quoted) { values.push(value.trim()); value = ""; }
    else if ((character === "\n" || character === "\r") && !quoted) { values.push(value.trim()); return values; }
    else value += character;
  }
  values.push(value.trim());
  return values;
}

function newIdempotencyKey(dataset: MigrationDataset): string {
  return `web-${dataset}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function jobLabel(job: MigrationJob): string {
  if (job.status === "committed") return "取込済み";
  if (job.status === "validated") return "取込可能";
  if (job.status === "committing") return "取込中";
  return "要修正";
}

export function DataMigrationPanel({ api, contextId, csrfToken, online, canImport, canExport, setNotice }: { api: MvpGateway; contextId: string; csrfToken: string; online: boolean; canImport: boolean; canExport: boolean; setNotice: (message: string) => void }) {
  const [dataset, setDataset] = useState<MigrationDataset>("fields");
  const [sourceName, setSourceName] = useState("");
  const [csv, setCsv] = useState("");
  const [columns, setColumns] = useState<string[]>([]);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [idempotencyKey, setIdempotencyKey] = useState(() => newIdempotencyKey("fields"));
  const [jobs, setJobs] = useState<MigrationJob[]>([]);
  const [selectedJob, setSelectedJob] = useState<MigrationJob | null>(null);
  const [busy, setBusy] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const definition = DATASETS[dataset];
  const missingMapping = useMemo(() => definition.fields.some((field) => field.required && !mapping[field.key]), [definition, mapping]);

  useEffect(() => {
    if (!canImport || !online) return;
    const controller = new AbortController();
    api.getMigrationJobs(contextId, controller.signal).then(({ jobs: current }) => setJobs(current)).catch(() => undefined);
    return () => controller.abort();
  }, [api, canImport, contextId, online]);

  const changeDataset = (next: MigrationDataset) => {
    setDataset(next); setCsv(""); setSourceName(""); setColumns([]); setMapping({}); setSelectedJob(null);
    setIdempotencyKey(newIdempotencyKey(next));
  };
  const selectFile = async (file?: File) => {
    if (!file) return;
    const text = await file.text();
    const detected = firstRecord(text);
    const fields = DATASETS[dataset].fields;
    setSourceName(file.name); setCsv(text); setColumns(detected); setIdempotencyKey(newIdempotencyKey(dataset));
    setMapping(Object.fromEntries(fields.map((field) => [field.key, detected.includes(field.key) ? field.key : ""])));
  };
  const stage = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!csv || missingMapping) { setNotice("CSVファイルと必須列の対応を確認してください。"); return; }
    setBusy(true);
    try {
      const job = await api.createMigrationJob(contextId, csrfToken, { dataset, sourceName, csv, mapping }, idempotencyKey);
      setSelectedJob(job); setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)]);
      setNotice(job.status === "validated" ? "重複検査が完了しました。内容を確認して取込を確定してください。" : "検査エラーがあります。元CSVを修正して再検査してください。");
    } catch { setNotice("CSVを検査できませんでした。列の対応とファイル形式を確認してください。"); }
    finally { setBusy(false); }
  };
  const commit = async (job: MigrationJob) => {
    setBusy(true);
    try {
      const committed = await api.commitMigrationJob(contextId, csrfToken, job.id, job.version);
      setSelectedJob(committed); setJobs((current) => current.map((item) => item.id === committed.id ? committed : item));
      setNotice(`${committed.validCount}件の取込を確定しました。`);
    } catch { setNotice("取込を確定できませんでした。ジョブの状態を更新して再確認してください。"); }
    finally { setBusy(false); }
  };
  const download = async (target: ExportDataset) => {
    setBusy(true);
    try {
      const result = await api.exportCsv(contextId, target, target === "fields" ? {} : { from: from || undefined, to: to || undefined });
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = result.fileName; anchor.click(); URL.revokeObjectURL(url);
      setNotice(`${result.fileName}を出力しました。`);
    } catch { setNotice("CSVを出力できませんでした。期間と権限を確認してください。"); }
    finally { setBusy(false); }
  };

  return <>
    {canImport && <section className="queue-panel"><h2>CSVデータ取込</h2><p>CSVを一度検査し、列の対応・重複・入力エラーを確認してから確定します。</p>
      <form className="record-form compact-form" onSubmit={(event) => void stage(event)}>
        <div className="form-grid"><label>取込対象<select value={dataset} onChange={(event) => changeDataset(event.target.value as MigrationDataset)}><option value="fields">圃場</option><option value="journals">作業記録</option><option value="pesticide_history">農薬履歴</option></select></label><label>CSVファイル<input type="file" accept=".csv,text/csv" onChange={(event) => void selectFile(event.target.files?.[0])}/></label></div>
        {columns.length > 0 && <fieldset><legend>列のマッピング</legend><p className="form-hint">検出した列: {columns.join("、")}</p><div className="form-grid">{definition.fields.map((field) => <label key={field.key}>{field.label}{field.required ? "（必須）" : "（任意）"}<select value={mapping[field.key] || ""} required={field.required} onChange={(event) => setMapping((current) => ({ ...current, [field.key]: event.target.value }))}><option value="">対応なし</option>{columns.map((column) => <option key={column} value={column}>{column}</option>)}</select></label>)}</div></fieldset>}
        <button className="primary-action" disabled={!online || busy || !csv || missingMapping}>重複と入力内容を検査</button>
      </form>
      {selectedJob?.rows && <article className="migration-result"><strong>{DATASETS[selectedJob.dataset].label}: {jobLabel(selectedJob)}</strong><p>全{selectedJob.rowCount}件／取込候補{selectedJob.validCount}件／重複{selectedJob.duplicateCount}件／エラー{selectedJob.errorCount}件</p>{selectedJob.rows.filter((row) => row.status === "invalid" || row.status === "duplicate").slice(0, 20).map((row) => <p key={row.lineNumber}>{row.lineNumber}行目: {row.status === "duplicate" ? `重複（${row.duplicateKey || "キー不明"}）` : row.errors.join("、")}</p>)}{selectedJob.status === "validated" && <button className="primary-action" disabled={!online || busy} onClick={() => void commit(selectedJob)}>検査済み{selectedJob.validCount}件の取込を確定</button>}</article>}
      {jobs.length > 0 && <div className="migration-history"><h3>直近の取込</h3>{jobs.slice(0, 5).map((job) => <p key={job.id}><strong>{job.sourceName}</strong> — {jobLabel(job)}（候補{job.validCount}／重複{job.duplicateCount}／エラー{job.errorCount}）</p>)}</div>}
    </section>}
    {canExport && <section className="queue-panel"><h2>CSVデータ出力</h2><p>現在の権限・担当範囲で閲覧できるデータだけをUTF-8 CSVで出力します。</p><div className="form-grid export-range"><label>開始日（任意）<input type="date" value={from} onChange={(event) => setFrom(event.target.value)}/></label><label>終了日（任意）<input type="date" value={to} onChange={(event) => setTo(event.target.value)}/></label></div><div className="queue-actions">{EXPORTS.map((item) => <button key={item.dataset} className="secondary-action" disabled={!online || busy || Boolean(from && to && from > to)} onClick={() => void download(item.dataset)}>{item.label}を出力</button>)}</div></section>}
  </>;
}
