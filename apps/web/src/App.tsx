import { lazy, Suspense, useEffect, useMemo, useState } from "react";
import type { FieldFeature, InventorySnapshot, JournalBootstrap, JournalEntry, MvpGateway, PesticideBootstrap, QueueSnapshot, TodayTask, WorkInstruction } from "./api";
import { demoAuthorization, type AppAuthorization, type TenantOption } from "./auth";
import { browserStorage, type JournalDraft, type StorageGateway } from "./storage";
import { synchronize } from "./sync";
import { evaluatePesticideUse, safetyReasonLabel } from "./pesticide-safety";
import { DataMigrationPanel } from "./DataMigrationPanel";
import { SchedulePage } from "./SchedulePage";

const FieldsPage = lazy(() => import("./FieldsPage").then((module) => ({ default: module.FieldsPage })));

type Route = "today" | "schedule" | "journal" | "pesticide" | "inventory" | "fields" | "more";
type PunchState = "idle" | "working" | "break";
type Theme = "field" | "dark" | "contrast";
type Locale = "ja" | "en";

const copy = {
  ja: { today: "今日", schedule: "予定", record: "記録", fields: "圃場", more: "その他", online: "オンライン", offline: "オフライン" },
  en: { today: "Today", schedule: "Schedule", record: "Record", fields: "Fields", more: "More", online: "Online", offline: "Offline" },
} as const;

const Icon = ({ name }: { name: "today" | "schedule" | "record" | "field" | "more" | "sync" | "clock" | "leaf" | "warning" }) => {
  const paths = {
    today: <><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4m8-4v4M3 10h18"/></>,
    schedule: <><path d="M4 5h16v14H4zM4 10h16M9 5v14M14 5v14"/><path d="M10 13h3v3h-3z"/></>,
    record: <><path d="M5 3h11l3 3v15H5z"/><path d="M8 12h8m-8 4h6M15 3v4h4"/></>,
    field: <><path d="M3 18c5-5 8-9 9-15 5 2 8 6 9 12-6 0-12 2-18 3Z"/><path d="M6 20c3-5 7-8 12-11"/></>,
    more: <><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></>,
    sync: <><path d="M20 7h-5V2"/><path d="M19 7a8 8 0 1 0 1 7"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v6l4 2"/></>,
    leaf: <><path d="M4 19C8 8 13 4 21 3c-1 9-6 15-17 16Z"/><path d="M5 19c4-5 8-8 13-11"/></>,
    warning: <><path d="M12 3 2 21h20L12 3Z"/><path d="M12 9v5m0 3v.1"/></>,
  };
  return <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>;
};

function eventId(): string {
  const time = Date.now().toString(16).padStart(12, "0").slice(-12);
  const bytes = crypto.getRandomValues(new Uint8Array(9));
  const random = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
  const variant = ((Number.parseInt(random[3], 16) & 0x3) | 0x8).toString(16);
  const raw = `${time}7${random.slice(0, 3)}${variant}${random.slice(3, 6)}${random.slice(6, 18)}`;
  return `${raw.slice(0, 8)}-${raw.slice(8, 12)}-${raw.slice(12, 16)}-${raw.slice(16, 20)}-${raw.slice(20)}`;
}

export function durationLabel(startedAt: string, endedAt: string): string {
  if (!startedAt || !endedAt) return "未入力";
  const [startHour, startMinute] = startedAt.split(":").map(Number);
  const [endHour, endMinute] = endedAt.split(":").map(Number);
  if (![startHour, startMinute, endHour, endMinute].every(Number.isFinite)) return "未入力";
  const minutes = Math.max(0, endHour * 60 + endMinute - startHour * 60 - startMinute);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours ? `${hours}時間${rest ? `${rest}分` : ""}` : `${rest}分`;
}

export function App({ api, csrfToken, storage = browserStorage, authorization = demoAuthorization, tenants = [], onTenantChange }: { api: MvpGateway; csrfToken: string; storage?: StorageGateway; authorization?: AppAuthorization; tenants?: TenantOption[]; onTenantChange?: (tenantId: string) => Promise<void> }) {
  const [route, setRoute] = useState<Route>("today");
  const [online, setOnline] = useState(() => navigator.onLine);
  const [pending, setPending] = useState(0);
  const [punch, setPunch] = useState<PunchState>("idle");
  const [theme, setTheme] = useState<Theme>("field");
  const [locale, setLocale] = useState<Locale>("ja");
  const [notice, setNotice] = useState("");
  const [tasks, setTasks] = useState<TodayTask[]>([]);
  const [instructions, setInstructions] = useState<WorkInstruction[]>([]);
  const [selectedInstructionId, setSelectedInstructionId] = useState<string | undefined>();
  const [selectedScheduleInstructionId, setSelectedScheduleInstructionId] = useState<string | undefined>();
  const [selectedJournalId, setSelectedJournalId] = useState<string | undefined>();
  const [journals, setJournals] = useState<JournalEntry[]>([]);
  const [syncRevision, setSyncRevision] = useState(0);
  const [queueCounts, setQueueCounts] = useState({ rejections: 0, conflicts: 0 });
  const [queues, setQueues] = useState<QueueSnapshot>({ rejections: [], conflicts: [] });
  const [reauthenticationRequired, setReauthenticationRequired] = useState(false);
  const effectiveAccessMode = reauthenticationRequired ? "locked" : authorization.accessMode;
  const canWrite = effectiveAccessMode === "online" || effectiveAccessMode === "offline-write";

  useEffect(() => {
    storage.pendingCount(authorization.context.tenantId).then(setPending).catch(() => setNotice("端末保存を確認できませんでした。もう一度お試しください。"));
    const wentOnline = () => setOnline(true);
    const wentOffline = () => setOnline(false);
    window.addEventListener("online", wentOnline);
    window.addEventListener("offline", wentOffline);
    navigator.storage?.persist?.().catch(() => undefined);
    return () => {
      window.removeEventListener("online", wentOnline);
      window.removeEventListener("offline", wentOffline);
    };
  }, [authorization.context.tenantId, storage]);

  useEffect(() => {
    const controller = new AbortController();
    const tenantId = authorization.context.tenantId;
    storage.getToday(tenantId).then((cached) => { if (!controller.signal.aborted) setTasks(cached); }).catch(() => undefined);
    if (online) api.getToday(authorization.context.contextId, controller.signal).then(async ({ tasks: current }) => {
      await storage.saveToday(tenantId, current);
      if (!controller.signal.aborted) setTasks(current);
    }).catch(() => { if (!controller.signal.aborted) setNotice("今日の作業を更新できませんでした。端末の保存内容を表示します。"); });
    return () => controller.abort();
  }, [api, authorization.context.contextId, authorization.context.tenantId, online, storage]);

  useEffect(() => {
    if (!online) return;
    const controller = new AbortController();
    api.getWorkInstructions(authorization.context.contextId, controller.signal).then(({ instructions: current }) => {
      if (!controller.signal.aborted) setInstructions(current);
    }).catch(() => undefined);
    api.getJournals(authorization.context.contextId, controller.signal).then(({ journals: current }) => {
      if (!controller.signal.aborted) setJournals(current);
    }).catch(() => undefined);
    return () => controller.abort();
  }, [api, authorization.context.contextId, online]);

  useEffect(() => {
    if (!online || authorization.accessMode !== "online" || reauthenticationRequired) return;
    const controller = new AbortController();
    synchronize({ api, storage, authorization, csrfToken, signal: controller.signal }).then(async (summary) => {
      if (controller.signal.aborted) return;
      setPending(summary.pending);
      setQueues(summary.queues);
      setQueueCounts(await storage.queueCounts(authorization.context.tenantId));
      if (summary.reauthenticationRequired) {
        setReauthenticationRequired(true);
        setTasks([]);
        setInstructions([]);
        setJournals([]);
        setQueues({ rejections: [], conflicts: [] });
        setQueueCounts({ rejections: 0, conflicts: 0 });
        setRoute("today");
        setNotice("権限が変更されました。未送信データは保持しています。再認証してください。");
      }
      else if (summary.rejected || summary.conflicts) setNotice(`同期結果を確認してください。差し戻し${summary.rejected}件、競合${summary.conflicts}件です。`);
    }).catch(() => { if (!controller.signal.aborted) setNotice("同期できませんでした。未送信データは端末に保持し、オンライン時に再試行します。"); });
    return () => controller.abort();
  }, [api, authorization, csrfToken, online, reauthenticationRequired, storage, syncRevision]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.lang = locale;
    document.documentElement.dir = "ltr";
  }, [theme, locale]);

  const navigate = (next: Route) => {
    setRoute(next);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const queue = async (kind: "journal" | "pesticide" | "punch" | "stock", payload: Record<string, unknown>, scope?: string): Promise<boolean> => {
    if (!canWrite) {
      setNotice(effectiveAccessMode === "offline-read" ? "オフライン猶予の読取期間です。再認証するまで新しい記録は確定できません。" : "認証の猶予が終了しました。再認証後に記録を再開できます。");
      return false;
    }
    const id = eventId();
    const occurredAt = new Date().toISOString();
    await storage.enqueue({
      eventUuid: id,
      bundleId: id,
      kind,
      payload,
      createdAt: occurredAt,
      occurredAt,
      tenantId: authorization.context.tenantId,
      authorizationSnapshotId: authorization.context.authorizationSnapshotId,
      membershipVersion: authorization.context.membershipVersion,
      scope,
    });
    setPending((value) => value + 1);
    if (online) setSyncRevision((value) => value + 1);
    setNotice(online ? "端末に保存しました。まもなく同期します。" : "端末に保存しました。電波が戻ると自動で同期します。");
    return true;
  };

  const punchAction = async (next: PunchState) => {
    const action = next === "working" ? (punch === "break" ? "resume" : "start") : next === "break" ? "break" : "finish";
    if (await queue("punch", { action, occurredAt: new Date().toISOString() })) setPunch(next);
  };

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">本文へ移動</a>
      <aside className="side-nav" aria-label="メインナビゲーション">
        <Brand />
        <Nav route={route} locale={locale} navigate={navigate} />
        <div className="side-foot">{authorization.context.tenantName}<br/><span>{authorization.context.roleLabel}</span></div>
      </aside>

      <div className="page-shell">
        <header className="topbar">
          <div className="mobile-brand"><Brand compact /></div>
          <div className="system-status" aria-live="polite">
            <span className={`connection ${online ? "is-online" : "is-offline"}`}><span className="status-dot" />{online ? copy[locale].online : copy[locale].offline}</span>
            <span className={`auth-state mode-${effectiveAccessMode}`}>{effectiveAccessMode === "online" ? "認証済み" : effectiveAccessMode === "offline-write" ? "オフライン記録可" : effectiveAccessMode === "offline-read" ? "オフライン読取のみ" : "再認証が必要"}</span>
            <span className="sync-state"><Icon name="sync" />未同期 {pending}件</span>
          </div>
          <div className="preferences">
            {tenants.length > 1 && <label className="tenant-switcher">組織<select aria-label="表示する組織" value={authorization.context.tenantId} onChange={(event) => void onTenantChange?.(event.target.value)}>{tenants.map((tenant) => <option key={tenant.id} value={tenant.id}>{tenant.name}</option>)}</select></label>}
            <button className="text-button" onClick={() => setLocale((value) => value === "ja" ? "en" : "ja")} aria-label="表示言語を切り替える">{locale === "ja" ? "EN" : "日本語"}</button>
            <button className="text-button" onClick={() => setTheme((value) => value === "field" ? "dark" : value === "dark" ? "contrast" : "field")} aria-label="表示テーマを切り替える">表示</button>
            <button className="avatar" aria-label={`${authorization.user.displayName}のアカウントメニュー`}>{authorization.user.initials}</button>
          </div>
        </header>

        <main id="main" tabIndex={-1}>
          {effectiveAccessMode !== "online" && <div className={`authorization-banner mode-${effectiveAccessMode}`} role="status"><strong>{effectiveAccessMode === "offline-write" ? "オフライン認証の猶予中" : effectiveAccessMode === "offline-read" ? "読取専用へ移行しました" : "認証の猶予が終了しました"}</strong><span>{effectiveAccessMode === "offline-write" ? "現場記録だけを端末へ保存できます。機微操作は利用できません。" : effectiveAccessMode === "offline-read" ? "下書きは保持されますが、再認証まで記録を確定できません。" : "未同期データを保持しています。オンライン復帰後に再認証してください。"}</span></div>}
          {reauthenticationRequired ? <section className="empty-page"><div className="empty-icon"><Icon name="warning"/></div><h1>再認証が必要です</h1><p>担当範囲または権限が変更されました。旧い参照データは端末から削除しました。未同期データは保持しています。</p><button className="primary-action" onClick={() => window.location.reload()}>再認証する</button></section> : <>
          {route === "today" && <TodayPage tasks={tasks} instructions={instructions} userName={authorization.user.displayName} punch={punch} punchAction={punchAction} navigate={navigate} openSchedule={(id) => { setSelectedScheduleInstructionId(id || undefined); navigate("schedule"); }} recordInstruction={(id) => { setSelectedJournalId(undefined); setSelectedInstructionId(id || undefined); navigate("journal"); }} />}
          {route === "schedule" && <SchedulePage
            instructions={instructions}
            selectedId={selectedScheduleInstructionId}
            onSelect={setSelectedScheduleInstructionId}
            recordInstruction={(id) => { setSelectedJournalId(undefined); setSelectedInstructionId(id); navigate("journal"); }}
          />}
          {route === "journal" && <JournalPage api={api} csrfToken={csrfToken} authorization={authorization} online={online} instructionId={selectedInstructionId} journalId={selectedJournalId} storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "pesticide" && <PesticidePage api={api} authorization={authorization} online={online} storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "inventory" && <InventoryPage api={api} authorization={authorization} online={online} storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "fields" && <Suspense fallback={<div className="page-content"><p role="status">圃場地図を読み込んでいます。</p></div>}><FieldsPage api={api} storage={storage} authorization={authorization} online={online} /></Suspense>}
          {route === "more" && <MorePage api={api} csrfToken={csrfToken} authorization={authorization} online={online} instructions={instructions} setInstructions={setInstructions} journals={journals} setJournals={setJournals} correctJournal={(id) => { setSelectedInstructionId(undefined); setSelectedJournalId(id); navigate("journal"); }} setNotice={setNotice} theme={theme} locale={locale} queueCounts={queueCounts} queues={queues} resolveConflict={async (id, choice) => {
            await api.resolveConflict(authorization.context.contextId, csrfToken, id, { choice });
            const next = await api.getQueues(authorization.context.contextId);
            await storage.saveServerQueues(authorization.context.tenantId, next);
            setQueues(next);
            setQueueCounts(await storage.queueCounts(authorization.context.tenantId));
            setNotice("競合の裁定を保存しました。");
          }} />}
          </>}
        </main>
      </div>

      <nav className="bottom-nav" aria-label="メインナビゲーション"><Nav route={route} locale={locale} navigate={navigate} /></nav>
      {notice && <div className="toast" role="status"><span>{notice}</span><button onClick={() => setNotice("")} aria-label="通知を閉じる">×</button></div>}
    </div>
  );
}

function Brand({ compact = false }: { compact?: boolean }) {
  return <div className={`brand ${compact ? "is-compact" : ""}`}><span className="brand-mark"><Icon name="leaf" /></span><span><strong>ISAS</strong>{!compact && <small>営農支援</small>}</span></div>;
}

function Nav({ route, locale, navigate }: { route: Route; locale: Locale; navigate: (route: Route) => void }) {
  const items: { route: Route; label: string; icon: "today" | "schedule" | "record" | "field" | "more" }[] = [
    { route: "today", label: copy[locale].today, icon: "today" },
    { route: "schedule", label: copy[locale].schedule, icon: "schedule" },
    { route: "journal", label: copy[locale].record, icon: "record" },
    { route: "fields", label: copy[locale].fields, icon: "field" },
    { route: "more", label: copy[locale].more, icon: "more" },
  ];
  return <div className="nav-items">{items.map((item) => <button key={item.route} className={route === item.route ? "active" : ""} aria-current={route === item.route ? "page" : undefined} onClick={() => navigate(item.route)}><Icon name={item.icon}/><span>{item.label}</span></button>)}</div>;
}

function TodayPage({ tasks, instructions, userName, punch, punchAction, navigate, openSchedule, recordInstruction }: { tasks: TodayTask[]; instructions: WorkInstruction[]; userName: string; punch: PunchState; punchAction: (state: PunchState) => Promise<void>; navigate: (route: Route) => void; openSchedule: (id: string) => void; recordInstruction: (id: string) => void }) {
  const date = new Intl.DateTimeFormat("ja-JP", { month: "long", day: "numeric", weekday: "short" }).format(new Date());
  return <div className="page-content">
    <section className="welcome-row">
      <div><p className="eyebrow">{date}</p><h1>おはようございます、{userName}さん</h1><p>今日の作業は{instructions.length || tasks.length}件です。{tasks.some((task) => task.status === "safety_check") && "安全確認が必要な作業があります。"}</p></div>
      <button className="primary-action desktop-only" onClick={() => recordInstruction("")}><Icon name="record"/>日誌をつける</button>
    </section>

    <section className={`punch-card state-${punch}`} aria-labelledby="punch-title">
      <div className="punch-icon"><Icon name="clock"/></div>
      <div className="punch-copy"><span className="section-kicker">今日の打刻</span><h2 id="punch-title">{punch === "idle" ? "まだ作業を開始していません" : punch === "working" ? "作業中です" : "休憩中です"}</h2><p>{punch === "idle" ? "開始時刻は日誌へ自動で入ります" : punch === "working" ? "開始 8:12 ・ 今日 1時間24分" : "休憩中は作業時間に含まれません"}</p></div>
      <div className="punch-actions">
        {punch === "idle" && <button className="punch-primary" onClick={() => punchAction("working")}>作業を始める</button>}
        {punch === "working" && <><button className="punch-secondary" onClick={() => punchAction("break")}>休憩する</button><button className="punch-primary" onClick={() => punchAction("idle")}>作業を終える</button></>}
        {punch === "break" && <button className="punch-primary" onClick={() => punchAction("working")}>作業に戻る</button>}
      </div>
    </section>

    <div className="content-grid">
      <section className="task-section" aria-labelledby="tasks-title">
        <div className="section-head"><div><span className="section-kicker">MY TASKS</span><h2 id="tasks-title">今日の作業</h2></div><button className="text-link" onClick={() => openSchedule("")}>すべて見る</button></div>
        <div className="task-list">{instructions.length === 0 && tasks.length === 0 && <p className="empty-list">今日の作業は登録されていません。</p>}{instructions.map((instruction) => <article className="task-card" key={instruction.id}>
          <div className="task-time"><strong>{new Intl.DateTimeFormat("ja-JP", { hour: "2-digit", minute: "2-digit" }).format(new Date(instruction.scheduledStart))}</strong><span>{{ issued: "未着手", in_progress: "進行中", completed: "完了", cancelled: "中止" }[instruction.status]}</span></div>
          <div className="task-main"><h3>{instruction.title}</h3><p>{instruction.fieldName}<span aria-hidden="true">・</span>{instruction.cropName || instruction.workType}</p>{instruction.details && <small>{instruction.details}</small>}</div>
          <div className="task-actions"><button className="task-button" onClick={() => openSchedule(instruction.id)}>予定</button><button className="task-button" onClick={() => recordInstruction(instruction.id)}>記録する</button></div>
        </article>)}{instructions.length === 0 && tasks.map((task) => <article className={`task-card ${task.status === "safety_check" ? "needs-check" : ""}`} key={task.id}>
          <div className="task-time"><strong>{task.time}</strong><span>{{ next: "次の作業", today: "今日", safety_check: "要安全確認", completed: "完了", cancelled: "中止" }[task.status]}</span></div>
          <div className="task-main"><h3>{task.work}</h3><p>{task.field}<span aria-hidden="true">・</span>{task.crop}</p></div>
          {task.status === "safety_check" ? <button className="task-button warning-button" onClick={() => navigate("pesticide")}><Icon name="warning"/>安全確認</button> : <button className="task-button" onClick={() => recordInstruction("")}>記録する</button>}
        </article>)}</div>
      </section>

      <aside className="quick-section" aria-labelledby="quick-title">
        <div className="section-head"><div><span className="section-kicker">QUICK ACTIONS</span><h2 id="quick-title">すぐに記録</h2></div></div>
        <div className="quick-grid">
          <button onClick={() => recordInstruction("")}><span className="quick-icon green"><Icon name="record"/></span><span><strong>作業日誌</strong><small>前回値から入力</small></span></button>
          <button onClick={() => navigate("pesticide")} aria-label="農薬記録を始める"><span className="quick-icon amber"><Icon name="leaf"/></span><span><strong>農薬記録</strong><small>使用基準を確認</small></span></button>
          <button onClick={() => navigate("fields")}><span className="quick-icon blue"><Icon name="field"/></span><span><strong>圃場を見る</strong><small>担当圃場を確認</small></span></button>
          <button onClick={() => navigate("inventory")}><span className="quick-icon green"><Icon name="sync"/></span><span><strong>在庫入出庫</strong><small>現場で出庫を記録</small></span></button>
        </div>
        <div className="safety-note"><Icon name="leaf"/><div><strong>農薬マスタは最新です</strong><span>2026年8月14日 06:10 更新</span></div></div>
      </aside>
    </div>
  </div>;
}

function JournalPage({ api, csrfToken, authorization, online, instructionId, journalId, storage, queue, navigate, setNotice }: { api: MvpGateway; csrfToken: string; authorization: AppAuthorization; online: boolean; instructionId?: string; journalId?: string; storage: StorageGateway; queue: (kind: "journal", payload: Record<string, unknown>, scope?: string) => Promise<boolean>; navigate: (route: Route) => void; setNotice: (message: string) => void }) {
  const [draft, setDraft] = useState<JournalDraft>({ id: "today-journal", aggregateId: crypto.randomUUID(), baseVersion: 0, baseValue: {}, instructionId, field: "", workType: "", startedAt: "", endedAt: "", memo: "", attachmentIds: [], updatedAt: new Date().toISOString() });
  const [bootstrap, setBootstrap] = useState<JournalBootstrap | null>(null);
  const [photoNames, setPhotoNames] = useState<string[]>([]);
  const [correctionReason, setCorrectionReason] = useState("");
  const templates = bootstrap?.templates.length ? bootstrap.templates : [
    { id: "builtin-water", name: "水管理", workType: "水管理", defaults: {}, version: 1 },
    { id: "builtin-mow", name: "草刈り", workType: "草刈り", defaults: {}, version: 1 },
    { id: "builtin-harvest", name: "収穫", workType: "収穫", defaults: {}, version: 1 },
  ];
  useEffect(() => {
    const controller = new AbortController();
    const tenantId = authorization.context.tenantId;
    const apply = (value: JournalBootstrap) => {
      if (journalId && value.previous?.id !== journalId) return;
      setBootstrap(value);
      const previous = value.previous?.body || {};
      setDraft((current) => ({
        ...current,
        aggregateId: journalId && value.previous ? value.previous.id : current.aggregateId,
        baseVersion: journalId && value.previous ? value.previous.version : current.baseVersion,
        baseValue: journalId && value.previous ? value.previous.body : current.baseValue,
        instructionId: value.instruction?.id || current.instructionId,
        fieldId: value.instruction?.fieldId || value.previous?.fieldId || current.fieldId,
        fieldGroupId: value.instruction?.fieldGroupId || value.previous?.fieldGroupId || current.fieldGroupId,
        field: value.instruction?.fieldName || String(previous.field || current.field),
        workType: value.instruction?.workType || String(previous.workType || current.workType),
        startedAt: value.punchSuggestion.startedAt || current.startedAt,
        endedAt: value.punchSuggestion.endedAt || current.endedAt,
        memo: current.memo || String(previous.memo || ""),
        updatedAt: new Date().toISOString(),
      }));
    };
    storage.getJournalBootstrap(tenantId).then((cached) => { if (cached && !controller.signal.aborted) apply(cached); }).catch(() => undefined);
    if (online) api.getJournalBootstrap(authorization.context.contextId, { instructionId, journalId }, controller.signal).then(async (current) => {
      await storage.saveJournalBootstrap(tenantId, current);
      if (!controller.signal.aborted) apply(current);
    }).catch(() => { if (!controller.signal.aborted) setNotice("入力候補を更新できませんでした。端末のテンプレートを使います。"); });
    return () => controller.abort();
  }, [api, authorization.context.contextId, authorization.context.tenantId, instructionId, journalId, online, setNotice, storage]);
  const update = (key: keyof JournalDraft, value: string) => setDraft((current) => ({ ...current, [key]: value, updatedAt: new Date().toISOString() }));
  const duration = useMemo(() => durationLabel(draft.startedAt, draft.endedAt), [draft.startedAt, draft.endedAt]);
  useEffect(() => {
    const timer = window.setTimeout(() => storage.saveDraft(draft).catch(() => setNotice("下書きを保存できませんでした。もう一度お試しください。")), 350);
    return () => window.clearTimeout(timer);
  }, [draft, setNotice, storage]);
  const saveDraft = async () => { await storage.saveDraft(draft); setNotice("下書きを端末に保存しました。"); };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const { aggregateId, baseVersion, baseValue, instructionId: selectedInstruction, fieldId, fieldGroupId, field, workType, startedAt, endedAt, memo, attachmentIds } = draft;
    if (journalId && !correctionReason.trim()) { setNotice("訂正理由を入力してください。"); return; }
    if (await queue("journal", { aggregateId, baseVersion, baseValue, instructionId: selectedInstruction, fieldId, attachmentIds, correctionReason: correctionReason || undefined, changes: { field, workType, startedAt, endedAt, memo, attachmentIds } }, fieldGroupId)) navigate("today");
  };
  const addPhoto = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!["image/jpeg", "image/png", "image/webp", "image/heic"].includes(file.type) || file.size > 10 * 1024 * 1024) {
      setNotice("写真はJPEG・PNG・WebP・HEICの10MB以下を選んでください。"); return;
    }
    const id = eventId();
    await storage.saveAttachment({ id, tenantId: authorization.context.tenantId, journalId: draft.aggregateId, fileName: file.name, capturedAt: new Date(file.lastModified || Date.now()).toISOString(), blob: file, ready: false });
    setDraft((current) => ({ ...current, attachmentIds: [...(current.attachmentIds || []), id], updatedAt: new Date().toISOString() }));
    setPhotoNames((current) => [...current, file.name]);
    setNotice("写真を端末に保存しました。日誌と一緒に同期します。");
    event.target.value = "";
  };

  return <div className="page-content narrow-page">
    <PageBack onBack={() => navigate("today")} />
    <div className="form-heading"><span className="section-kicker">WORK JOURNAL</span><h1>作業日誌をつける</h1><p>打刻と前回値から入力済みです。違うところだけ直してください。</p></div>
    <form className="record-form" onSubmit={submit}>
      <div className="autosave-line"><span className="status-dot"/>端末へ自動保存</div>
      <fieldset><legend>作業の内容</legend><div className="form-grid">
        <label>圃場<input value={draft.field} onChange={(event) => update("field", event.target.value)} required /></label>
        <label>作業<input value={draft.workType} onChange={(event) => update("workType", event.target.value)} required /></label>
      </div><div className="template-row"><span>よく使う作業</span>{templates.map((template) => <button key={template.id} type="button" onClick={() => setDraft((current) => ({ ...current, ...template.defaults, workType: template.workType, updatedAt: new Date().toISOString() }))}>{template.name}</button>)}</div></fieldset>
      <fieldset><legend>作業した時間</legend>{bootstrap?.punchSuggestion.warning && <p className="field-warning" role="status">{bootstrap.punchSuggestion.warning === "missing_start" ? "開始打刻がありません。時刻を確認して入力してください。" : "終了打刻がありません。終了後に時刻を入力してください。"}</p>}<div className="form-grid time-grid"><label>開始<input type="time" value={draft.startedAt} onChange={(event) => update("startedAt", event.target.value)} required/></label><label>終了<input type="time" value={draft.endedAt} onChange={(event) => update("endedAt", event.target.value)} required/></label><div className="duration"><span>作業時間</span><strong>{duration}</strong></div></div></fieldset>
      <fieldset><legend>メモ・写真</legend><label>作業メモ<textarea rows={4} value={draft.memo} onChange={(event) => update("memo", event.target.value)} placeholder="気づいたことを入力（任意）"/></label><label className="secondary-action attachment-picker">写真を追加<input type="file" accept="image/jpeg,image/png,image/webp,image/heic" onChange={(event) => void addPhoto(event)}/></label>{photoNames.length > 0 && <ul className="attachment-list">{photoNames.map((name, index) => <li key={`${name}-${index}`}>{name}</li>)}</ul>}</fieldset>
      {journalId && <fieldset><legend>訂正理由</legend><label>差し戻しへの対応<textarea rows={3} value={correctionReason} onChange={(event) => setCorrectionReason(event.target.value)} required placeholder="訂正した内容と理由を入力"/></label></fieldset>}
      <div className="form-actions"><button className="secondary-action" type="button" onClick={saveDraft}>下書き保存</button><button className="primary-action" type="submit">この内容で記録</button></div>
    </form>
  </div>;
}

function PesticidePage({ api, authorization, online, storage, queue, navigate, setNotice }: { api: MvpGateway; authorization: AppAuthorization; online: boolean; storage: StorageGateway; queue: (kind: "pesticide", payload: Record<string, unknown>, scope?: string) => Promise<boolean>; navigate: (route: Route) => void; setNotice: (message: string) => void }) {
  const [fields, setFields] = useState<FieldFeature[]>([]);
  const [fieldId, setFieldId] = useState("");
  const [bootstrap, setBootstrap] = useState<PesticideBootstrap | null>(null);
  const [chemicalId, setChemicalId] = useState("");
  const [dilution, setDilution] = useState(1000);
  const [appliedOn, setAppliedOn] = useState(() => new Date().toISOString().slice(0, 10));
  const [plannedHarvestOn, setPlannedHarvestOn] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);

  useEffect(() => {
    let active = true;
    const tenantId = authorization.context.tenantId;
    storage.getFields(tenantId).then((cached) => { if (active && cached.length) { setFields(cached); setFieldId((current) => current || cached[0].id); } }).catch(() => undefined);
    if (online) api.getFields(authorization.context.contextId).then(async (current) => {
      await storage.saveFields(tenantId, current.features);
      if (active) { setFields(current.features); setFieldId((value) => value || current.features[0]?.id || ""); }
    }).catch(() => setNotice("担当圃場を更新できませんでした。端末の保存内容を使います。"));
    return () => { active = false; };
  }, [api, authorization.context.contextId, authorization.context.tenantId, online, setNotice, storage]);

  useEffect(() => {
    if (!fieldId) { setBootstrap(null); return; }
    let active = true;
    const tenantId = authorization.context.tenantId;
    storage.getPesticideBootstrap(tenantId, fieldId).then((cached) => { if (active && cached) { setBootstrap(cached); setChemicalId((current) => current || cached.chemicals[0]?.id || ""); } }).catch(() => undefined);
    if (online) api.getPesticideBootstrap(authorization.context.contextId, fieldId).then(async (current) => {
      await storage.savePesticideBootstrap(tenantId, current);
      if (active) { setBootstrap(current); setChemicalId(current.chemicals[0]?.id || ""); setAcknowledged(false); }
    }).catch(() => setNotice("農薬マスタを更新できませんでした。端末の保存内容で確認します。"));
    return () => { active = false; };
  }, [api, authorization.context.contextId, authorization.context.tenantId, fieldId, online, setNotice, storage]);

  const chemical = bootstrap?.chemicals.find((item) => item.id === chemicalId) || null;
  const decision = useMemo(() => evaluatePesticideUse({ bootstrap, chemical, cropName: bootstrap?.field.cropName || "", dilution, appliedOn, plannedHarvestOn: plannedHarvestOn || undefined }), [appliedOn, bootstrap, chemical, dilution, plannedHarvestOn]);
  const managerOverride = authorization.context.capabilities.includes("pesticide:override");
  const canSubmit = decision.status === "safe" || (decision.status === "warning" && acknowledged && (!decision.requiresManagerOverride || managerOverride));
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!bootstrap || !chemical || !canSubmit) return;
    const data = Object.fromEntries(new FormData(event.currentTarget));
    if (await queue("pesticide", {
      aggregateId: eventId(), fieldId: bootstrap.field.id, cropName: bootstrap.field.cropName, chemicalId: chemical.id,
      dilution, amount: Number(data.amount), targetPest: data.targetPest, equipment: data.equipment,
      workerName: data.workerName, plannedHarvestOn: plannedHarvestOn || null, appliedOn,
      safetyDecision: decision, warningAcknowledged: acknowledged, managerOverride: decision.requiresManagerOverride && managerOverride,
    }, bootstrap.field.fieldGroupId)) navigate("today");
  };
  const isWarning = decision.status !== "safe";
  return <div className="page-content narrow-page">
    <PageBack onBack={() => navigate("today")} />
    <div className="form-heading"><span className="section-kicker">PESTICIDE RECORD</span><h1>農薬記録</h1><p>端末保存したマスタと使用履歴で、散布前に使用基準を確認します。</p></div>
    <form className="record-form" onSubmit={(event) => void submit(event)}>
      <div className={`freshness ${decision.reasons.includes("master_stale") || decision.reasons.includes("master_missing") ? "is-warning" : ""}`}><Icon name="sync"/><div><strong>{bootstrap?.release ? `安全基準 ${bootstrap.release.version}` : "安全基準データがありません"}</strong><span>{bootstrap?.release ? `有効期限 ${new Date(bootstrap.release.validUntil).toLocaleString("ja-JP")}・最終同期 ${new Date(bootstrap.release.syncedAt).toLocaleString("ja-JP")}` : "オンラインでマスタを取得してください"}</span></div></div>
      <fieldset><legend>散布する場所と作物</legend><div className="form-grid"><label>圃場<select value={fieldId} onChange={(event) => { setFieldId(event.target.value); setBootstrap(null); setChemicalId(""); setAcknowledged(false); }} required><option value="">選択してください</option>{fields.map((field) => <option key={field.id} value={field.id}>{field.properties.name}</option>)}</select></label><label>作物<input value={bootstrap?.field.cropName || ""} readOnly required/></label></div></fieldset>
      <fieldset><legend>薬剤と使用量</legend><label>薬剤名<select value={chemicalId} onChange={(event) => { setChemicalId(event.target.value); setAcknowledged(false); }} required><option value="">選択してください</option>{bootstrap?.chemicals.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><div className="form-grid"><label>希釈倍率<input inputMode="numeric" type="number" value={dilution} onChange={(event) => { setDilution(Number(event.target.value)); setAcknowledged(false); }} required/><span className="field-suffix">倍</span></label><label>散布量<input name="amount" inputMode="decimal" type="number" min="0.01" step="0.01" defaultValue="120" required/><span className="field-suffix">L</span></label></div></fieldset>
      <div className={`safety-result ${isWarning ? "is-warning" : "is-safe"}`} role={isWarning ? "alert" : "status"}><Icon name={isWarning ? "warning" : "leaf"}/><div><strong>{decision.status === "blocked" ? "この内容では記録できません" : isWarning ? "使用前に警告を確認してください" : "使用基準の範囲内です"}</strong>{decision.reasons.length ? <ul>{decision.reasons.map((reason) => <li key={reason}>{safetyReasonLabel[reason]}</li>)}</ul> : <p>適用作物・希釈倍率・使用回数・収穫前日数を確認しました。</p>}{decision.requiresManagerOverride && !managerOverride && <p>この端末のマスタが古いため、責任者がオンライン更新または承認してください。</p>}</div></div>
      {decision.status === "warning" && <label className="check-row"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)}/><span>{decision.requiresManagerOverride ? "責任者として古いマスタと警告内容を確認しました" : "警告内容と使用履歴を確認しました"}</span></label>}
      <fieldset><legend>記録に必要な情報</legend><div className="form-grid"><label>対象病害虫<input name="targetPest" defaultValue="ノビエ" required/></label><label>使用器具<input name="equipment" defaultValue="背負式噴霧器" required/></label><label>作業者<input name="workerName" defaultValue={authorization.user.displayName} required/></label><label>散布日<input type="date" value={appliedOn} onChange={(event) => { setAppliedOn(event.target.value); setAcknowledged(false); }} required/></label><label>収穫予定日<input type="date" value={plannedHarvestOn} onChange={(event) => { setPlannedHarvestOn(event.target.value); setAcknowledged(false); }}/></label></div></fieldset>
      <div className="form-actions"><button className="secondary-action" type="button" onClick={() => navigate("today")}>戻る</button><button className="primary-action" type="submit" disabled={!canSubmit}>安全確認して記録</button></div>
    </form>
  </div>;
}

function InventoryPage({ api, authorization, online, storage, queue, navigate, setNotice }: { api: MvpGateway; authorization: AppAuthorization; online: boolean; storage: StorageGateway; queue: (kind: "stock", payload: Record<string, unknown>) => Promise<boolean>; navigate: (route: Route) => void; setNotice: (message: string) => void }) {
  const [snapshot, setSnapshot] = useState<InventorySnapshot>({ balances: [], alerts: [] });
  const [physical, setPhysical] = useState<Record<string, string>>({});
  const canAdjust = authorization.context.capabilities.includes("inventory:adjust");
  useEffect(() => {
    let active = true; const tenantId = authorization.context.tenantId;
    storage.getInventory(tenantId).then((cached) => { if (active && cached) setSnapshot(cached); }).catch(() => undefined);
    if (online) api.getInventory(authorization.context.contextId).then(async (current) => { await storage.saveInventory(tenantId, current); if (active) setSnapshot(current); }).catch(() => setNotice("在庫を更新できませんでした。端末の保存内容を表示します。"));
    return () => { active = false; };
  }, [api, authorization.context.contextId, authorization.context.tenantId, online, setNotice, storage]);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = Object.fromEntries(new FormData(form));
    if (await queue("stock", { aggregateId: eventId(), chemicalId: data.chemicalId, eventType: data.eventType, quantity: Number(data.quantity), reason: data.reason })) { form.reset(); navigate("today"); }
  };
  const adjust = async (alert: NonNullable<QueueSnapshot["stockAlerts"]>[number]) => {
    const balance = snapshot.balances.find((item) => item.chemicalId === alert.chemicalId)?.quantity || 0;
    const actual = Number(physical[alert.id]);
    if (!Number.isFinite(actual)) { setNotice("実棚数量を入力してください。"); return; }
    if (await queue("stock", { aggregateId: eventId(), chemicalId: alert.chemicalId, eventType: "adjustment", quantity: actual - balance, reason: `実棚${actual}を確認`, alertId: alert.id })) setNotice("棚卸し調整イベントを端末に保存しました。");
  };
  return <div className="page-content narrow-page"><PageBack onBack={() => navigate("today")}/><div className="form-heading"><span className="section-kicker">INVENTORY</span><h1>在庫入出庫</h1><p>残高は直接編集せず、入出庫と棚卸し調整の履歴から計算します。</p></div>
    <section className="queue-panel"><h2>現在の在庫</h2>{snapshot.balances.length ? snapshot.balances.map((item) => <article key={item.chemicalId}><strong>{item.name}</strong><p>{item.registrationNumber}・残量 <b>{item.quantity}</b></p></article>) : <p>端末に在庫データがありません。オンラインで更新してください。</p>}</section>
    <form className="record-form" onSubmit={(event) => void submit(event)}><fieldset><legend>入出庫イベント</legend><div className="form-grid"><label>資材<select name="chemicalId" required><option value="">選択してください</option>{snapshot.balances.map((item) => <option key={item.chemicalId} value={item.chemicalId}>{item.name}</option>)}</select></label><label>種別<select name="eventType" defaultValue="withdrawal"><option value="withdrawal">出庫</option><option value="receipt">入庫</option></select></label><label>数量<input name="quantity" type="number" min="0.01" step="0.01" required/></label><label>理由<input name="reason" defaultValue="散布用出庫" required/></label></div></fieldset><button className="primary-action">端末に入出庫を記録</button></form>
    {canAdjust && snapshot.alerts.length > 0 && <section className="queue-panel"><h2>マイナス在庫の裁定</h2>{snapshot.alerts.map((alert) => <article key={alert.id}><strong>{alert.name || snapshot.balances.find((item) => item.chemicalId === alert.chemicalId)?.name || "農薬"}</strong><p>同期後残高 {alert.negativeQuantity}。実棚を確認して差分を追記してください。</p><div className="queue-actions"><label>実棚数量<input type="number" step="0.01" value={physical[alert.id] || ""} onChange={(event) => setPhysical((current) => ({ ...current, [alert.id]: event.target.value }))}/></label><button className="primary-action" onClick={() => void adjust(alert)}>調整イベントを記録</button></div></article>)}</section>}
  </div>;
}

function PageBack({ onBack }: { onBack: () => void }) { return <button className="back-button" onClick={onBack}>← 今日の作業へ戻る</button>; }
function MorePage({ api, csrfToken, authorization, online, instructions, setInstructions, journals, setJournals, correctJournal, setNotice, theme, locale, queueCounts, queues, resolveConflict }: { api: MvpGateway; csrfToken: string; authorization: AppAuthorization; online: boolean; instructions: WorkInstruction[]; setInstructions: React.Dispatch<React.SetStateAction<WorkInstruction[]>>; journals: JournalEntry[]; setJournals: React.Dispatch<React.SetStateAction<JournalEntry[]>>; correctJournal: (id: string) => void; setNotice: (message: string) => void; theme: Theme; locale: Locale; queueCounts: { rejections: number; conflicts: number }; queues: QueueSnapshot; resolveConflict: (id: string, choice: "server" | "device") => Promise<void> }) {
  const manager = authorization.context.capabilities.includes("instruction:manage");
  const reviewer = authorization.context.capabilities.includes("journal:review");
  const migrationManager = authorization.context.capabilities.includes("migration:manage");
  const exportReader = authorization.context.capabilities.includes("export:read");
  const [returnReasons, setReturnReasons] = useState<Record<string, string>>({});
  const [assignees, setAssignees] = useState<Record<string, string>>({});
  const refreshJournals = async () => setJournals((await api.getJournals(authorization.context.contextId)).journals);
  const review = async (journal: JournalEntry, action: "approve" | "return") => {
    const reason = returnReasons[journal.id]?.trim();
    if (action === "return" && !reason) { setNotice("差し戻し理由を入力してください。"); return; }
    await api.reviewJournal(authorization.context.contextId, csrfToken, journal.id, { action, expectedVersion: journal.version, reason });
    await refreshJournals(); setNotice(action === "approve" ? "日誌を承認しました。" : "日誌を担当者へ差し戻しました。");
  };
  const createInstruction = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = Object.fromEntries(new FormData(form));
    const created = await api.createWorkInstruction(authorization.context.contextId, csrfToken, { ...data, scheduledStart: new Date(String(data.scheduledStart)).toISOString(), scheduledEnd: new Date(String(data.scheduledEnd)).toISOString(), priority: Number(data.priority) });
    setInstructions((current) => [...current, created]); form.reset(); setNotice("作業指示を発行しました。");
  };
  const reassign = async (instruction: WorkInstruction) => {
    const assigneeUserId = assignees[instruction.id]?.trim();
    if (!assigneeUserId) { setNotice("新しい担当者IDを入力してください。"); return; }
    const result = await api.reassignWorkInstruction(authorization.context.contextId, csrfToken, instruction.id, { assigneeUserId, expectedVersion: instruction.version });
    setInstructions((current) => current.map((item) => item.id === instruction.id ? { ...item, version: result.version, assignment: { id: result.assignmentId, assigneeUserId: result.assigneeUserId, version: 1 } } : item));
    setNotice("担当者を変更しました。");
  };
  return <div className="page-content narrow-page"><div className="form-heading"><span className="section-kicker">SETTINGS</span><h1>その他</h1><p>表示と端末状態、同期で判断が必要な項目を確認できます。</p></div><div className="settings-list"><div><span>表示テーマ</span><strong>{theme === "field" ? "屋外向け" : theme === "dark" ? "ダーク" : "高コントラスト"}</strong></div><div><span>表示言語</span><strong>{locale === "ja" ? "日本語" : "English"}</strong></div><div><span>オフライン保持</span><strong>利用可能</strong></div><div><span>差し戻しキュー</span><strong>{queueCounts.rejections}件</strong></div><div><span>競合キュー</span><strong>{queueCounts.conflicts}件</strong></div></div>
    {manager && <section className="queue-panel"><h2>作業指示を発行</h2><form className="record-form compact-form" onSubmit={(event) => void createInstruction(event)}><div className="form-grid"><label>圃場ID<input name="fieldId" required/></label><label>担当者ID<input name="assigneeUserId" required/></label><label>指示名<input name="title" required/></label><label>作業種別<input name="workType" required/></label><label>開始予定<input name="scheduledStart" type="datetime-local" required/></label><label>終了予定<input name="scheduledEnd" type="datetime-local" required/></label><label>優先度<select name="priority" defaultValue="1"><option value="0">高</option><option value="1">通常</option><option value="2">低</option></select></label><label>詳細<textarea name="details" rows={2}/></label></div><button className="primary-action" disabled={!online}>オンラインで発行</button></form>
      <div>{instructions.map((instruction) => <article key={instruction.id}><strong>{instruction.title}</strong><p>{instruction.fieldName}・担当 {instruction.assignment?.assigneeUserId || "未割当"}・version {instruction.version}</p><div className="queue-actions"><label>新しい担当者ID<input value={assignees[instruction.id] || ""} onChange={(event) => setAssignees((current) => ({ ...current, [instruction.id]: event.target.value }))}/></label><button className="secondary-action" disabled={!online} onClick={() => void reassign(instruction)}>再割当</button></div></article>)}</div></section>}
    {reviewer && <section className="queue-panel"><h2>日誌の承認・差し戻し</h2>{journals.filter((journal) => journal.status === "submitted" || journal.status === "corrected").map((journal) => <article key={journal.id}><strong>{journal.fieldName || String(journal.body.field || "作業日誌")}</strong><p>{String(journal.body.workType || "")}・{String(journal.body.startedAt || "")}〜{String(journal.body.endedAt || "")}</p><p>{String(journal.body.memo || "メモなし")}</p><label>差し戻し理由<textarea value={returnReasons[journal.id] || ""} onChange={(event) => setReturnReasons((current) => ({ ...current, [journal.id]: event.target.value }))}/></label><div className="queue-actions"><button className="secondary-action" onClick={() => void review(journal, "return")}>理由を付けて差し戻す</button><button className="primary-action" onClick={() => void review(journal, "approve")}>承認する</button></div></article>)}</section>}
    {journals.some((journal) => journal.workerUserId === authorization.user.id && journal.status === "returned") && <section className="queue-panel"><h2>訂正が必要な日誌</h2>{journals.filter((journal) => journal.workerUserId === authorization.user.id && journal.status === "returned").map((journal) => <article key={journal.id}><strong>{journal.fieldName || "作業日誌"}</strong><p>{journal.returnReason ? `差し戻し理由: ${journal.returnReason}` : `${String(journal.body.workType || "")}を確認し、訂正理由とともに再提出してください。`}</p><button className="primary-action" onClick={() => correctJournal(journal.id)}>訂正する</button></article>)}</section>}
    {queues.rejections.length > 0 && <section className="queue-panel"><h2>差し戻し</h2>{queues.rejections.map((item) => <article key={item.id}><strong>{item.reason}</strong><p>束: {item.bundleId}</p><p>回復操作: {item.recoveryAction}</p></article>)}</section>}
    {queues.conflicts.length > 0 && <section className="queue-panel"><h2>競合の裁定</h2>{queues.conflicts.map((item) => <article key={item.id}><strong>{item.conflictingFields.join("、")} が競合しています</strong><p>サーバ値: {JSON.stringify(item.currentValue)}</p><p>端末値: {JSON.stringify(item.proposedValue)}</p><div className="queue-actions"><button className="secondary-action" onClick={() => void resolveConflict(item.id, "server")}>サーバ値を採用</button><button className="primary-action" onClick={() => void resolveConflict(item.id, "device")}>端末値を採用</button></div></article>)}</section>}
    {(migrationManager || exportReader) && <DataMigrationPanel api={api} contextId={authorization.context.contextId} csrfToken={csrfToken} online={online} canImport={migrationManager} canExport={exportReader} setNotice={setNotice}/>}
  </div>;
}
