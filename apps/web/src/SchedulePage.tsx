import { useEffect, useMemo, useState } from "react";
import type { WorkInstruction } from "./api";

const WINDOW_DAYS = 14;
const DAY = new Intl.DateTimeFormat("ja-JP", { month: "numeric", day: "numeric" });
const WEEKDAY = new Intl.DateTimeFormat("ja-JP", { weekday: "short" });
const DATE_TIME = new Intl.DateTimeFormat("ja-JP", { month: "numeric", day: "numeric", weekday: "short", hour: "2-digit", minute: "2-digit" });
const STATUS: Record<WorkInstruction["status"], string> = { issued: "未着手", in_progress: "進行中", completed: "完了", cancelled: "中止" };

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
    <div className="schedule-heading"><div><span className="section-kicker">WORK SCHEDULE</span><h1>作業予定</h1><p>作業指示と同じ予定を、日付をまたぐタイムラインと現場用リストで確認できます。</p></div><div className="schedule-navigation" aria-label="表示期間"><button className="secondary-action" onClick={() => setWindowStart((current) => addDays(current, -WINDOW_DAYS))}>前の14日</button><button className="secondary-action" onClick={() => setWindowStart(startOfWeek(new Date()))}>今日</button><button className="secondary-action" onClick={() => setWindowStart((current) => addDays(current, WINDOW_DAYS))}>次の14日</button></div></div>
    <p className="schedule-period">{DAY.format(days[0])}〜{DAY.format(days[days.length - 1])}・{visible.length}件</p>

    <section className="gantt-panel" aria-label="作業予定タイムライン">
      <div className="gantt-scroll"><div className="gantt-grid">
        <div className="gantt-header"><strong>作業指示</strong>{days.map((day) => <span key={dateKey(day)} className={dateKey(day) === currentKey ? "today" : ""}><b>{DAY.format(day)}</b><small>{WEEKDAY.format(day)}</small></span>)}</div>
        {visible.length === 0 && <p className="gantt-empty">この期間に作業指示はありません。</p>}
        {visible.map((instruction) => {
          const range = instructionDays(instruction, days)!;
          const selected = instruction.id === selectedId;
          return <div className={`gantt-row ${selected ? "selected" : ""}`} key={instruction.id}>
            <button className="gantt-label" aria-pressed={selected} onClick={() => onSelect(instruction.id)}><strong>{instruction.title}</strong><small>{instruction.fieldName || "圃場未設定"}</small></button>
            {days.map((day) => <span aria-hidden="true" className={`gantt-cell ${dateKey(day) === currentKey ? "today" : ""}`} key={dateKey(day)}/>)}
            <button className={`gantt-bar status-${instruction.status}`} style={{ gridColumn: `${range.start + 2} / ${range.end + 3}` }} aria-label={`${instruction.title}、${DATE_TIME.format(new Date(instruction.scheduledStart))}から${DATE_TIME.format(new Date(instruction.scheduledEnd))}`} aria-pressed={selected} onClick={() => onSelect(instruction.id)}><span>{instruction.title}</span></button>
          </div>;
        })}
      </div></div>
    </section>

    <section className="mobile-schedule-list" aria-labelledby="mobile-schedule-title"><h2 id="mobile-schedule-title">作業リスト</h2>{visible.length === 0 && <p>この期間に作業指示はありません。</p>}{visible.map((instruction) => <button key={instruction.id} className={instruction.id === selectedId ? "selected" : ""} aria-pressed={instruction.id === selectedId} onClick={() => onSelect(instruction.id)}><span><strong>{instruction.title}</strong><small>{instruction.fieldName || "圃場未設定"}・{instruction.workType}</small></span><span><b>{DAY.format(new Date(instruction.scheduledStart))}</b><small>{STATUS[instruction.status]}</small></span></button>)}</section>

    {selectedInstruction && <section className="schedule-detail" aria-live="polite"><div><span className={`status-pill status-${selectedInstruction.status}`}>{STATUS[selectedInstruction.status]}</span><h2>{selectedInstruction.title}</h2><p>{selectedInstruction.fieldName || "圃場未設定"}・{selectedInstruction.cropName || selectedInstruction.workType}</p></div><dl><div><dt>予定開始</dt><dd>{DATE_TIME.format(new Date(selectedInstruction.scheduledStart))}</dd></div><div><dt>予定終了</dt><dd>{DATE_TIME.format(new Date(selectedInstruction.scheduledEnd))}</dd></div><div><dt>担当者</dt><dd>{selectedInstruction.assignment?.assigneeUserId || "未割当"}</dd></div></dl><button className="primary-action" disabled={selectedInstruction.status === "cancelled"} onClick={() => recordInstruction(selectedInstruction.id)}>この作業の日誌をつける</button></section>}
  </div>;
}
