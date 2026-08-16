import { useEffect, useMemo, useState } from "react";
import type { ExportDataset, MigrationDataset, MigrationJob, MvpGateway } from "./api";
import { tr } from "./i18n";

type FieldDefinition = { key: string; label: string; required: boolean };

function datasetDefinitions(): Record<MigrationDataset, { label: string; fields: FieldDefinition[] }> { return {
  fields: { label: tr("datamigrationpanel.l7.1"), fields: [
    { key: "externalKey", label: tr("datamigrationpanel.l8.2"), required: true }, { key: "name", label: tr("datamigrationpanel.l8.3"), required: true },
    { key: "fieldGroupId", label: tr("datamigrationpanel.l9.4"), required: true }, { key: "geometryWkt", label: tr("datamigrationpanel.l9.5"), required: true },
    { key: "cropName", label: tr("datamigrationpanel.l10.6"), required: false }, { key: "timezone", label: tr("datamigrationpanel.l10.7"), required: false },
  ] },
  journals: { label: tr("datamigrationpanel.l12.8"), fields: [
    { key: "externalKey", label: tr("datamigrationpanel.l13.9"), required: true }, { key: "fieldExternalKey", label: tr("datamigrationpanel.l13.10"), required: true },
    { key: "workerUserId", label: tr("datamigrationpanel.l14.11"), required: true }, { key: "workType", label: tr("datamigrationpanel.l14.12"), required: true },
    { key: "workedOn", label: tr("datamigrationpanel.l15.13"), required: true }, { key: "startedAt", label: tr("datamigrationpanel.l15.14"), required: true },
    { key: "endedAt", label: tr("datamigrationpanel.l16.15"), required: true }, { key: "memo", label: tr("datamigrationpanel.l16.16"), required: false },
  ] },
  pesticide_history: { label: tr("datamigrationpanel.l18.17"), fields: [
    { key: "fieldExternalKey", label: tr("datamigrationpanel.l19.18"), required: true }, { key: "cropName", label: tr("datamigrationpanel.l19.19"), required: true },
    { key: "registrationNumber", label: tr("datamigrationpanel.l20.20"), required: true }, { key: "usageCount", label: tr("datamigrationpanel.l20.21"), required: true },
    { key: "lastAppliedOn", label: tr("datamigrationpanel.l21.22"), required: true },
  ] },
}; }

function exportDefinitions(): Array<{ dataset: ExportDataset; label: string }> { return [
  { dataset: "journals", label: tr("datamigrationpanel.l26.23") }, { dataset: "fields", label: tr("datamigrationpanel.l26.24") }, { dataset: "pesticide-records", label: tr("datamigrationpanel.l26.25") }, { dataset: "jgap-inventory", label: tr("datamigrationpanel.l26.26") },
]; }

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
  if (job.status === "committed") return tr("datamigrationpanel.l50.27");
  if (job.status === "validated") return tr("datamigrationpanel.l51.28");
  if (job.status === "committing") return tr("datamigrationpanel.l52.29");
  return tr("datamigrationpanel.l53.30");
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
  const definitions = datasetDefinitions();
  const definition = definitions[dataset];
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
    const fields = definitions[dataset].fields;
    setSourceName(file.name); setCsv(text); setColumns(detected); setIdempotencyKey(newIdempotencyKey(dataset));
    setMapping(Object.fromEntries(fields.map((field) => [field.key, detected.includes(field.key) ? field.key : ""])));
  };
  const stage = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!csv || missingMapping) { setNotice(tr("datamigrationpanel.l92.31")); return; }
    setBusy(true);
    try {
      const job = await api.createMigrationJob(contextId, csrfToken, { dataset, sourceName, csv, mapping }, idempotencyKey);
      setSelectedJob(job); setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)]);
      setNotice(job.status === "validated" ? tr("datamigrationpanel.l97.32") : tr("datamigrationpanel.l97.33"));
    } catch { setNotice(tr("datamigrationpanel.l98.34")); }
    finally { setBusy(false); }
  };
  const commit = async (job: MigrationJob) => {
    setBusy(true);
    try {
      const committed = await api.commitMigrationJob(contextId, csrfToken, job.id, job.version);
      setSelectedJob(committed); setJobs((current) => current.map((item) => item.id === committed.id ? committed : item));
      setNotice(tr("datamigrationpanel.l106.35", [committed.validCount]));
    } catch { setNotice(tr("datamigrationpanel.l107.36")); }
    finally { setBusy(false); }
  };
  const download = async (target: ExportDataset) => {
    setBusy(true);
    try {
      const result = await api.exportCsv(contextId, target, target === "fields" ? {} : { from: from || undefined, to: to || undefined });
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = result.fileName; anchor.click(); URL.revokeObjectURL(url);
      setNotice(tr("datamigrationpanel.l116.37", [result.fileName]));
    } catch { setNotice(tr("datamigrationpanel.l117.38")); }
    finally { setBusy(false); }
  };

  return <>
    {canImport && <section className="queue-panel"><h2>{tr("datamigrationpanel.l122.39")}</h2><p>{tr("datamigrationpanel.l122.40")}</p>
      <form className="record-form compact-form" onSubmit={(event) => void stage(event)}>
        <div className="form-grid"><label>{tr("datamigrationpanel.l124.41")}<select value={dataset} onChange={(event) => changeDataset(event.target.value as MigrationDataset)}><option value="fields">{tr("datamigrationpanel.l124.42")}</option><option value="journals">{tr("datamigrationpanel.l124.43")}</option><option value="pesticide_history">{tr("datamigrationpanel.l124.44")}</option></select></label><label>{tr("datamigrationpanel.l124.45")}<input type="file" accept=".csv,text/csv" onChange={(event) => void selectFile(event.target.files?.[0])}/></label></div>
        {columns.length > 0 && <fieldset><legend>{tr("datamigrationpanel.l125.46")}</legend><p className="form-hint">{tr("datamigrationpanel.l125.47")} {columns.join(", ")}</p><div className="form-grid">{definition.fields.map((field) => <label key={field.key}>{field.label}{field.required ? tr("datamigrationpanel.l125.48") : tr("datamigrationpanel.l125.49")}<select value={mapping[field.key] || ""} required={field.required} onChange={(event) => setMapping((current) => ({ ...current, [field.key]: event.target.value }))}><option value="">{tr("datamigrationpanel.l125.50")}</option>{columns.map((column) => <option key={column} value={column}>{column}</option>)}</select></label>)}</div></fieldset>}
        <button className="primary-action" disabled={!online || busy || !csv || missingMapping}>{tr("datamigrationpanel.l126.51")}</button>
      </form>
      {selectedJob?.rows && <article className="migration-result"><strong>{definitions[selectedJob.dataset].label}: {jobLabel(selectedJob)}</strong><p>{tr("datamigrationpanel.l128.52")}{selectedJob.rowCount}{tr("datamigrationpanel.l128.53")}{selectedJob.validCount}{tr("datamigrationpanel.l128.54")}{selectedJob.duplicateCount}{tr("datamigrationpanel.l128.55")}{selectedJob.errorCount}{tr("datamigrationpanel.l128.56")}</p>{selectedJob.rows.filter((row) => row.status === "invalid" || row.status === "duplicate").slice(0, 20).map((row) => <p key={row.lineNumber}>{row.lineNumber}{tr("datamigrationpanel.l128.57")} {row.status === "duplicate" ? tr("datamigrationpanel.l128.58", [row.duplicateKey || tr("datamigrationpanel.unknown_key")]) : row.errors.join(", ")}</p>)}{selectedJob.status === "validated" && <button className="primary-action" disabled={!online || busy} onClick={() => void commit(selectedJob)}>{tr("datamigrationpanel.l128.59")}{selectedJob.validCount}{tr("datamigrationpanel.l128.60")}</button>}</article>}
      {jobs.length > 0 && <div className="migration-history"><h3>{tr("datamigrationpanel.l129.61")}</h3>{jobs.slice(0, 5).map((job) => <p key={job.id}><strong>{job.sourceName}</strong> — {jobLabel(job)}{tr("datamigrationpanel.l129.62")}{job.validCount}{tr("datamigrationpanel.l129.63")}{job.duplicateCount}{tr("datamigrationpanel.l129.64")}{job.errorCount}）</p>)}</div>}
    </section>}
    {canExport && <section className="queue-panel"><h2>{tr("datamigrationpanel.l131.65")}</h2><p>{tr("datamigrationpanel.l131.66")}</p><div className="form-grid export-range"><label>{tr("datamigrationpanel.l131.67")}<input type="date" value={from} onChange={(event) => setFrom(event.target.value)}/></label><label>{tr("datamigrationpanel.l131.68")}<input type="date" value={to} onChange={(event) => setTo(event.target.value)}/></label></div><div className="queue-actions">{exportDefinitions().map((item) => <button key={item.dataset} className="secondary-action" disabled={!online || busy || Boolean(from && to && from > to)} onClick={() => void download(item.dataset)}>{item.label}{tr("datamigrationpanel.l131.69")}</button>)}</div></section>}
  </>;
}
