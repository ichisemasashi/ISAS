import { useEffect, useMemo, useState } from "react";
import type { WorkInstruction } from "./api";
import { formatDate, tr } from "./i18n";

const WINDOW_DAYS = 14;
const dayLabel = (value: Date) => formatDate(value, { month: "numeric", day: "numeric" });
const weekdayLabel = (value: Date) => formatDate(value, { weekday: "short" });
const dateTimeLabel = (value: Date) => formatDate(value, { month: "numeric", day: "numeric", weekday: "short", hour: "2-digit", minute: "2-digit" });
const statusKeys: Record<WorkInstruction["status"], string> = { issued: "schedulepage.l8.1", in_progress: "schedulepage.l8.2", completed: "schedulepage.l8.3", cancelled: "schedulepage.l8.4" };
const statusLabel = (status: WorkInstruction["status"]) => tr(statusKeys[status]);

function startOfDay(value: Date): Date {
  const result = new Date(value);
  result.setHours(0, 0, 0, 0);
  return result;
}

function addDays(value: Date, amount: number): Date {
  const result = new Date(value);
  result.setDate(result.getDate() + amount);
  return result;
}

function startOfWeek(value: Date): Date {
  const result = startOfDay(value);
  result.setDate(result.getDate() - result.getDay());
  return result;
}

function dateKey(value: Date): string {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}

function instructionDays(instruction: WorkInstruction, days: Date[]): { start: number; end: number } | null {
  const start = dateKey(startOfDay(new Date(instruction.scheduledStart)));
  const end = dateKey(startOfDay(new Date(instruction.scheduledEnd)));
  const keys = days.map(dateKey);
  if (end < keys[0] || start > keys[keys.length - 1]) return null;
  const first = Math.max(0, keys.findIndex((key) => key >= start));
  let last = -1;
  for (let index = keys.length - 1; index >= 0; index -= 1) if (keys[index] <= end) { last = index; break; }
  if (last < 0) last = keys.length - 1;
  return { start: first, end: last };
}

export function SchedulePage({ instructions, selectedId, onSelect, recordInstruction }: { instructions: WorkInstruction[]; selectedId?: string; onSelect: (id: string) => void; recordInstruction: (id: string) => void }) {
  const selectedInstruction = instructions.find((item) => item.id === selectedId);
  const [windowStart, setWindowStart] = useState(() => startOfWeek(selectedInstruction ? new Date(selectedInstruction.scheduledStart) : new Date()));
  useEffect(() => {
    if (selectedInstruction) setWindowStart(startOfWeek(new Date(selectedInstruction.scheduledStart)));
  }, [selectedInstruction?.id]);
  const days = useMemo(() => Array.from({ length: WINDOW_DAYS }, (_, index) => addDays(windowStart, index)), [windowStart]);
  const visible = useMemo(() => instructions
    .filter((instruction) => instructionDays(instruction, days))
    .sort((left, right) => Date.parse(left.scheduledStart) - Date.parse(right.scheduledStart)), [days, instructions]);
  const currentKey = dateKey(new Date());

  return <div className="page-content schedule-page">
    <div className="schedule-heading"><div><span className="section-kicker">WORK SCHEDULE</span><h1>{tr("schedulepage.l57.5")}</h1><p>{tr("schedulepage.l57.6")}</p></div><div className="schedule-navigation" aria-label={tr("schedulepage.l57.7")}><button className="secondary-action" onClick={() => setWindowStart((current) => addDays(current, -WINDOW_DAYS))}>{tr("schedulepage.l57.8")}</button><button className="secondary-action" onClick={() => setWindowStart(startOfWeek(new Date()))}>{tr("schedulepage.l57.9")}</button><button className="secondary-action" onClick={() => setWindowStart((current) => addDays(current, WINDOW_DAYS))}>{tr("schedulepage.l57.10")}</button></div></div>
    <p className="schedule-period">{dayLabel(days[0])}〜{dayLabel(days[days.length - 1])}{tr("schedulepage.l58.11")}{visible.length}{tr("schedulepage.l58.12")}</p>

    <section className="gantt-panel" aria-label={tr("schedulepage.l60.13")}>
      <div className="gantt-scroll"><div className="gantt-grid">
        <div className="gantt-header"><strong>{tr("schedulepage.l62.14")}</strong>{days.map((day) => <span key={dateKey(day)} className={dateKey(day) === currentKey ? "today" : ""}><b>{dayLabel(day)}</b><small>{weekdayLabel(day)}</small></span>)}</div>
        {visible.length === 0 && <p className="gantt-empty">{tr("schedulepage.l63.15")}</p>}
        {visible.map((instruction) => {
          const range = instructionDays(instruction, days)!;
          const selected = instruction.id === selectedId;
          return <div className={`gantt-row ${selected ? "selected" : ""}`} key={instruction.id}>
            <button className="gantt-label" aria-pressed={selected} onClick={() => onSelect(instruction.id)}><strong>{instruction.title}</strong><small>{instruction.fieldName || tr("schedulepage.l68.16")}{instruction.resourceConflicts?.length ? tr("schedulepage.l68.17", [instruction.resourceConflicts.length]) : ""}</small></button>
            {days.map((day) => <span aria-hidden="true" className={`gantt-cell ${dateKey(day) === currentKey ? "today" : ""}`} key={dateKey(day)}/>)}
            <button className={`gantt-bar status-${instruction.status}`} style={{ gridColumn: `${range.start + 2} / ${range.end + 3}` }} aria-label={tr("schedulepage.l70.18", [instruction.title, dateTimeLabel(new Date(instruction.scheduledStart)), dateTimeLabel(new Date(instruction.scheduledEnd)), instruction.progressPercent || 0])} aria-pressed={selected} onClick={() => onSelect(instruction.id)}><span>{instruction.title} {instruction.progressPercent || 0}%</span></button>
          </div>;
        })}
      </div></div>
    </section>

    <section className="mobile-schedule-list" aria-labelledby="mobile-schedule-title"><h2 id="mobile-schedule-title">{tr("schedulepage.l76.19")}</h2>{visible.length === 0 && <p>{tr("schedulepage.l76.20")}</p>}{visible.map((instruction) => <button key={instruction.id} className={instruction.id === selectedId ? "selected" : ""} aria-pressed={instruction.id === selectedId} onClick={() => onSelect(instruction.id)}><span><strong>{instruction.title}</strong><small>{instruction.fieldName || tr("schedulepage.l76.21")}{tr("schedulepage.l76.22")}{instruction.workType}{instruction.resourceConflicts?.length ? tr("schedulepage.l76.23") : ""}</small></span><span><b>{dayLabel(new Date(instruction.scheduledStart))}</b><small>{statusLabel(instruction.status)}{tr("schedulepage.l76.24")}{instruction.progressPercent || 0}%</small></span></button>)}</section>

    {selectedInstruction && <section className="schedule-detail" aria-live="polite"><div><span className={`status-pill status-${selectedInstruction.status}`}>{statusLabel(selectedInstruction.status)}</span><h2>{selectedInstruction.title}</h2><p>{selectedInstruction.fieldName || tr("schedulepage.l78.25")}{tr("schedulepage.l78.26")}{selectedInstruction.cropName || selectedInstruction.workType}{selectedInstruction.varietyName ? `（${selectedInstruction.varietyName}）` : ""}</p>{selectedInstruction.plannedAreaM2 != null && <small>{tr("schedulepage.l78.27")} {selectedInstruction.plannedAreaM2}{tr("schedulepage.l78.28")} {selectedInstruction.targetYieldKg ?? tr("schedulepage.l78.29")}kg</small>}</div><dl><div><dt>{tr("schedulepage.l78.30")}</dt><dd>{dateTimeLabel(new Date(selectedInstruction.scheduledStart))}</dd></div><div><dt>{tr("schedulepage.l78.31")}</dt><dd>{dateTimeLabel(new Date(selectedInstruction.scheduledEnd))}</dd></div><div><dt>{tr("schedulepage.l78.32")}</dt><dd>{selectedInstruction.progressPercent || 0}%</dd></div><div><dt>{tr("schedulepage.l78.33")}</dt><dd>{selectedInstruction.assignment?.assigneeUserId || tr("schedulepage.l78.34")}</dd></div><div><dt>{tr("schedulepage.l78.35")}</dt><dd>{selectedInstruction.dependencies?.length || 0}{tr("schedulepage.l78.36")}</dd></div><div><dt>resource</dt><dd>{selectedInstruction.resources?.map((item) => item.name).join(", ") || tr("schedulepage.l78.37")}</dd></div></dl>{selectedInstruction.resourceConflicts?.length ? <p role="alert">{tr("schedulepage.l78.38")}{selectedInstruction.resourceConflicts.length}{tr("schedulepage.l78.39")}</p> : null}<button className="primary-action" disabled={selectedInstruction.status === "cancelled"} onClick={() => recordInstruction(selectedInstruction.id)}>{tr("schedulepage.l78.40")}</button></section>}
  </div>;
}
