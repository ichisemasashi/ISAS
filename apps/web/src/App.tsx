import { lazy, Suspense, useEffect, useMemo, useState } from "react";
import type { FieldFeature, InventorySnapshot, JournalBootstrap, JournalEntry, MvpGateway, PesticideBootstrap, QueueSnapshot, TodayTask, WorkInstruction } from "./api";
import { demoAuthorization, type AppAuthorization, type TenantOption } from "./auth";
import { browserStorage, type JournalDraft, type StorageGateway } from "./storage";
import { synchronize } from "./sync";
import { evaluatePesticideUse, safetyReasonLabel } from "./pesticide-safety";
import { DataMigrationPanel } from "./DataMigrationPanel";
import { SecurityAdministrationPanel } from "./SecurityAdministrationPanel";
import { SchedulePage } from "./SchedulePage";
import { TenantAnalyticsPanel } from "./TenantAnalyticsPanel";
import { LocationTrackingPanel } from "./LocationTrackingPanel";
import { applyDocumentLocale, formatDate, formatNumber, localeProfiles, resolveLocale, setActiveLocale, translate, tr, type Locale } from "./i18n";

const FieldsPage = lazy(() => import("./FieldsPage").then((module) => ({ default: module.FieldsPage })));

type Route = "today" | "schedule" | "journal" | "pesticide" | "inventory" | "fields" | "more";
type PunchState = "idle" | "working" | "break";
type Theme = "field" | "dark" | "contrast";
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
  if (!startedAt || !endedAt) return tr("app.l44.1");
  const [startHour, startMinute] = startedAt.split(":").map(Number);
  const [endHour, endMinute] = endedAt.split(":").map(Number);
  if (![startHour, startMinute, endHour, endMinute].every(Number.isFinite)) return tr("app.l47.2");
  const minutes = Math.max(0, endHour * 60 + endMinute - startHour * 60 - startMinute);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours ? tr("app.l51.3", [hours, rest ? tr("app.duration_minutes", [rest]) : ""]) : tr("app.l51.4", [rest]);
}

export function App({ api, csrfToken, storage = browserStorage, authorization = demoAuthorization, tenants = [], onTenantChange, onLogout }: { api: MvpGateway; csrfToken: string; storage?: StorageGateway; authorization?: AppAuthorization; tenants?: TenantOption[]; onTenantChange?: (tenantId: string) => Promise<void>; onLogout?: () => Promise<void> }) {
  const [route, setRoute] = useState<Route>("today");
  const [online, setOnline] = useState(() => navigator.onLine);
  const [pending, setPending] = useState(0);
  const [punch, setPunch] = useState<PunchState>("idle");
  const [theme, setTheme] = useState<Theme>("field");
  const [locale, setLocale] = useState<Locale>(() => {
    const requested = import.meta.env.DEV ? new URLSearchParams(window.location.search).get("locale") : null;
    return requested ? resolveLocale(requested) : "ja";
  });
  setActiveLocale(locale);
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
    storage.pendingCount(authorization.context.tenantId).then(setPending).catch(() => setNotice(tr("app.l79.5")));
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
    }).catch(() => { if (!controller.signal.aborted) setNotice(tr("app.l98.6")); });
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
        setNotice(tr("app.l130.7"));
      }
      else if (summary.rejected || summary.conflicts) setNotice(tr("app.l132.8", [summary.rejected, summary.conflicts]));
    }).catch(() => { if (!controller.signal.aborted) setNotice(tr("app.l133.9")); });
    return () => controller.abort();
  }, [api, authorization, csrfToken, online, reauthenticationRequired, storage, syncRevision]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    applyDocumentLocale(document.documentElement, locale);
  }, [theme, locale]);

  const navigate = (next: Route) => {
    setRoute(next);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const queue = async (kind: "journal" | "pesticide" | "punch" | "stock", payload: Record<string, unknown>, scope?: string): Promise<boolean> => {
    if (!canWrite) {
      setNotice(effectiveAccessMode === "offline-read" ? tr("app.l149.10") : tr("app.l149.11"));
      return false;
    }
    const id = eventId();
    const occurredAt = new Date().toISOString();
    try {
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
    } catch {
      setNotice(tr("app.l168.12"));
      return false;
    }
    setPending((value) => value + 1);
    if (online) setSyncRevision((value) => value + 1);
    setNotice(online ? tr("app.l173.13") : tr("app.l173.14"));
    return true;
  };

  const punchAction = async (next: PunchState) => {
    const action = next === "working" ? (punch === "break" ? "resume" : "start") : next === "break" ? "break" : "finish";
    if (await queue("punch", { action, occurredAt: new Date().toISOString() })) setPunch(next);
  };

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">{tr("app.l184.15")}</a>
      <aside className="side-nav" aria-label={tr("app.l185.16")}>
        <Brand />
        <Nav route={route} locale={locale} navigate={navigate} />
        <div className="side-foot">{authorization.context.tenantName}<br/><span>{authorization.context.roleLabel}</span></div>
      </aside>

      <div className="page-shell">
        <header className="topbar">
          <div className="mobile-brand"><Brand compact /></div>
          <div className="system-status" aria-live="polite">
            <span className={`connection ${online ? "is-online" : "is-offline"}`}><span className="status-dot" />{translate(locale, online ? "online" : "offline")}</span>
            <span className={`auth-state mode-${effectiveAccessMode}`}>{effectiveAccessMode === "online" ? tr("app.l196.17") : effectiveAccessMode === "offline-write" ? tr("app.l196.18") : effectiveAccessMode === "offline-read" ? tr("app.l196.19") : tr("app.l196.20")}</span>
            <span className="sync-state"><Icon name="sync" />{tr("app.l197.21")} {pending}{tr("app.l197.22")}</span>
          </div>
          <div className="preferences">
            {tenants.length > 1 && <label className="tenant-switcher">{tr("app.l200.23")}<select aria-label={tr("app.l200.24")} value={authorization.context.tenantId} onChange={(event) => void onTenantChange?.(event.target.value)}>{tenants.map((tenant) => <option key={tenant.id} value={tenant.id}>{tenant.name}</option>)}</select></label>}
            <button className="text-button" onClick={() => setLocale((value) => value === "ja" ? "en" : "ja")} aria-label={tr("app.l201.25")}>{locale === "ja" ? "EN" : tr("app.l201.26")}</button>
            <button className="text-button" onClick={() => setTheme((value) => value === "field" ? "dark" : value === "dark" ? "contrast" : "field")} aria-label={tr("app.l202.27")}>{tr("app.l202.28")}</button>
            <button className="avatar" aria-label={tr("app.l203.29", [authorization.user.displayName])} onClick={() => navigate("more")}>{authorization.user.initials}</button>
          </div>
        </header>

        <main id="main" tabIndex={-1}>
          {effectiveAccessMode !== "online" && <div className={`authorization-banner mode-${effectiveAccessMode}`} role="status"><strong>{effectiveAccessMode === "offline-write" ? tr("app.l208.30") : effectiveAccessMode === "offline-read" ? tr("app.l208.31") : tr("app.l208.32")}</strong><span>{effectiveAccessMode === "offline-write" ? tr("app.l208.33") : effectiveAccessMode === "offline-read" ? tr("app.l208.34") : tr("app.l208.35")}</span></div>}
          {reauthenticationRequired ? <section className="empty-page"><div className="empty-icon"><Icon name="warning"/></div><h1>{tr("app.l209.36")}</h1><p>{tr("app.l209.37")}</p><button className="primary-action" onClick={() => window.location.reload()}>{tr("app.l209.38")}</button></section> : <>
          {route === "today" && <TodayPage api={api} csrfToken={csrfToken} authorization={authorization} online={online} locale={locale} setNotice={setNotice} tasks={tasks} instructions={instructions} userName={authorization.user.displayName} punch={punch} punchAction={punchAction} navigate={navigate} openSchedule={(id) => { setSelectedScheduleInstructionId(id || undefined); navigate("schedule"); }} recordInstruction={(id) => { setSelectedJournalId(undefined); setSelectedInstructionId(id || undefined); navigate("journal"); }} />}
          {route === "schedule" && <SchedulePage
            instructions={instructions}
            selectedId={selectedScheduleInstructionId}
            onSelect={setSelectedScheduleInstructionId}
            recordInstruction={(id) => { setSelectedJournalId(undefined); setSelectedInstructionId(id); navigate("journal"); }}
          />}
          {route === "journal" && <JournalPage api={api} csrfToken={csrfToken} authorization={authorization} online={online} instructionId={selectedInstructionId} journalId={selectedJournalId} storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "pesticide" && <PesticidePage api={api} authorization={authorization} online={online} storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "inventory" && <InventoryPage api={api} authorization={authorization} online={online} storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "fields" && <Suspense fallback={<div className="page-content"><p role="status">{tr("app.l220.39")}</p></div>}><FieldsPage api={api} storage={storage} authorization={authorization} online={online} csrfToken={csrfToken} setNotice={setNotice} /></Suspense>}
          {route === "more" && <MorePage api={api} csrfToken={csrfToken} authorization={authorization} online={online} instructions={instructions} setInstructions={setInstructions} journals={journals} setJournals={setJournals} correctJournal={(id) => { setSelectedInstructionId(undefined); setSelectedJournalId(id); navigate("journal"); }} setNotice={setNotice} theme={theme} locale={locale} queueCounts={queueCounts} queues={queues} onLogout={onLogout} resolveConflict={async (id, choice) => {
            await api.resolveConflict(authorization.context.contextId, csrfToken, id, { choice });
            const next = await api.getQueues(authorization.context.contextId);
            await storage.saveServerQueues(authorization.context.tenantId, next);
            setQueues(next);
            setQueueCounts(await storage.queueCounts(authorization.context.tenantId));
            setNotice(tr("app.l227.40"));
          }} />}
          </>}
        </main>
      </div>

      <nav className="bottom-nav" aria-label={tr("app.l233.41")}><Nav route={route} locale={locale} navigate={navigate} /></nav>
      {notice && <div className="toast" role="status"><span>{notice}</span><button onClick={() => setNotice("")} aria-label={tr("app.l234.42")}>×</button></div>}
    </div>
  );
}

function Brand({ compact = false }: { compact?: boolean }) {
  return <div className={`brand ${compact ? "is-compact" : ""}`}><span className="brand-mark"><Icon name="leaf" /></span><span><strong>ISAS</strong>{!compact && <small>{tr("app.l240.43")}</small>}</span></div>;
}

function Nav({ route, locale, navigate }: { route: Route; locale: Locale; navigate: (route: Route) => void }) {
  const items: { route: Route; label: string; icon: "today" | "schedule" | "record" | "field" | "more" }[] = [
    { route: "today", label: translate(locale, "today"), icon: "today" },
    { route: "schedule", label: translate(locale, "schedule"), icon: "schedule" },
    { route: "journal", label: translate(locale, "record"), icon: "record" },
    { route: "fields", label: translate(locale, "fields"), icon: "field" },
    { route: "more", label: translate(locale, "more"), icon: "more" },
  ];
  return <div className="nav-items">{items.map((item) => <button key={item.route} className={route === item.route ? "active" : ""} aria-current={route === item.route ? "page" : undefined} onClick={() => navigate(item.route)}><Icon name={item.icon}/><span>{item.label}</span></button>)}</div>;
}

function TodayPage({ api, csrfToken, authorization, online, locale, setNotice, tasks, instructions, userName, punch, punchAction, navigate, openSchedule, recordInstruction }: { api: MvpGateway; csrfToken: string; authorization: AppAuthorization; online: boolean; locale: Locale; setNotice: (message: string) => void; tasks: TodayTask[]; instructions: WorkInstruction[]; userName: string; punch: PunchState; punchAction: (state: PunchState) => Promise<void>; navigate: (route: Route) => void; openSchedule: (id: string) => void; recordInstruction: (id: string) => void }) {
  const date = formatDate(new Date(), { month: "long", day: "numeric", weekday: "short" });
  return <div className="page-content">
    <section className="welcome-row">
      <div><p className="eyebrow">{date}</p><h1>{tr("app.l258.44")}{userName}{tr("app.l258.45")}</h1><p>{tr("app.l258.46")}{instructions.length || tasks.length}{tr("app.l258.47")}{tasks.some((task) => task.status === "safety_check") && tr("app.l258.48")}</p></div>
      <button className="primary-action desktop-only" onClick={() => recordInstruction("")}><Icon name="record"/>{tr("app.l259.49")}</button>
    </section>

    <section className={`punch-card state-${punch}`} aria-labelledby="punch-title">
      <div className="punch-icon"><Icon name="clock"/></div>
      <div className="punch-copy"><span className="section-kicker">{tr("app.l264.50")}</span><h2 id="punch-title">{punch === "idle" ? tr("app.l264.51") : punch === "working" ? tr("app.l264.52") : tr("app.l264.53")}</h2><p>{punch === "idle" ? tr("app.l264.54") : punch === "working" ? tr("app.l264.55") : tr("app.l264.56")}</p></div>
      <div className="punch-actions">
        {punch === "idle" && <button className="punch-primary" onClick={() => punchAction("working")}>{tr("app.l266.57")}</button>}
        {punch === "working" && <><button className="punch-secondary" onClick={() => punchAction("break")}>{tr("app.l267.58")}</button><button className="punch-primary" onClick={() => punchAction("idle")}>{tr("app.l267.59")}</button></>}
        {punch === "break" && <button className="punch-primary" onClick={() => punchAction("working")}>{tr("app.l268.60")}</button>}
      </div>
    </section>
    <LocationTrackingPanel api={api} contextId={authorization.context.contextId} csrfToken={csrfToken} locale={locale} online={online} punch={punch} setNotice={setNotice}/>

    <div className="content-grid">
      <section className="task-section" aria-labelledby="tasks-title">
        <div className="section-head"><div><span className="section-kicker">MY TASKS</span><h2 id="tasks-title">{tr("app.l275.61")}</h2></div><button className="text-link" onClick={() => openSchedule("")}>{tr("app.l275.62")}</button></div>
        <div className="task-list">{instructions.length === 0 && tasks.length === 0 && <p className="empty-list">{tr("app.l276.63")}</p>}{instructions.map((instruction) => <article className="task-card" key={instruction.id}>
          <div className="task-time"><strong>{formatDate(instruction.scheduledStart, { hour: "2-digit", minute: "2-digit" })}</strong><span>{{ issued: tr("app.l277.64"), in_progress: tr("app.l277.65"), completed: tr("app.l277.66"), cancelled: tr("app.l277.67") }[instruction.status]}</span></div>
          <div className="task-main"><h3>{instruction.title}</h3><p>{instruction.fieldName}<span aria-hidden="true">{tr("app.l278.68")}</span>{instruction.cropName || instruction.workType}</p>{instruction.details && <small>{instruction.details}</small>}</div>
          <div className="task-actions"><button className="task-button" onClick={() => openSchedule(instruction.id)}>{tr("app.l279.69")}</button><button className="task-button" onClick={() => recordInstruction(instruction.id)}>{tr("app.l279.70")}</button></div>
        </article>)}{instructions.length === 0 && tasks.map((task) => <article className={`task-card ${task.status === "safety_check" ? "needs-check" : ""}`} key={task.id}>
          <div className="task-time"><strong>{task.time}</strong><span>{{ next: tr("app.l281.71"), today: tr("app.l281.72"), safety_check: tr("app.l281.73"), completed: tr("app.l281.74"), cancelled: tr("app.l281.75") }[task.status]}</span></div>
          <div className="task-main"><h3>{task.work}</h3><p>{task.field}<span aria-hidden="true">{tr("app.l282.76")}</span>{task.crop}</p></div>
          {task.status === "safety_check" ? <button className="task-button warning-button" onClick={() => navigate("pesticide")}><Icon name="warning"/>{tr("app.l283.77")}</button> : <button className="task-button" onClick={() => recordInstruction("")}>{tr("app.l283.78")}</button>}
        </article>)}</div>
      </section>

      <aside className="quick-section" aria-labelledby="quick-title">
        <div className="section-head"><div><span className="section-kicker">QUICK ACTIONS</span><h2 id="quick-title">{tr("app.l288.79")}</h2></div></div>
        <div className="quick-grid">
          <button onClick={() => recordInstruction("")}><span className="quick-icon green"><Icon name="record"/></span><span><strong>{tr("app.l290.80")}</strong><small>{tr("app.l290.81")}</small></span></button>
          <button onClick={() => navigate("pesticide")} aria-label={tr("app.l291.82")}><span className="quick-icon amber"><Icon name="leaf"/></span><span><strong>{tr("app.l291.83")}</strong><small>{tr("app.l291.84")}</small></span></button>
          <button onClick={() => navigate("fields")}><span className="quick-icon blue"><Icon name="field"/></span><span><strong>{tr("app.l292.85")}</strong><small>{tr("app.l292.86")}</small></span></button>
          <button onClick={() => navigate("inventory")}><span className="quick-icon green"><Icon name="sync"/></span><span><strong>{tr("app.l293.87")}</strong><small>{tr("app.l293.88")}</small></span></button>
        </div>
        <div className="safety-note"><Icon name="leaf"/><div><strong>{tr("app.l295.89")}</strong><span>{tr("app.l295.90")}</span></div></div>
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
    { id: "builtin-water", name: tr("app.l307.91"), workType: tr("app.l307.92"), defaults: {}, version: 1 },
    { id: "builtin-mow", name: tr("app.l308.93"), workType: tr("app.l308.94"), defaults: {}, version: 1 },
    { id: "builtin-harvest", name: tr("app.l309.95"), workType: tr("app.l309.96"), defaults: {}, version: 1 },
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
    }).catch(() => { if (!controller.signal.aborted) setNotice(tr("app.l338.97")); });
    return () => controller.abort();
  }, [api, authorization.context.contextId, authorization.context.tenantId, instructionId, journalId, online, setNotice, storage]);
  const update = (key: keyof JournalDraft, value: string) => setDraft((current) => ({ ...current, [key]: value, updatedAt: new Date().toISOString() }));
  const duration = useMemo(() => durationLabel(draft.startedAt, draft.endedAt), [draft.startedAt, draft.endedAt]);
  useEffect(() => {
    const timer = window.setTimeout(() => storage.saveDraft(draft).catch(() => setNotice(tr("app.l344.98"))), 350);
    return () => window.clearTimeout(timer);
  }, [draft, setNotice, storage]);
  const saveDraft = async () => { await storage.saveDraft(draft); setNotice(tr("app.l347.99")); };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const { aggregateId, baseVersion, baseValue, instructionId: selectedInstruction, fieldId, fieldGroupId, field, workType, startedAt, endedAt, memo, attachmentIds } = draft;
    if (journalId && !correctionReason.trim()) { setNotice(tr("app.l351.100")); return; }
    if (await queue("journal", { aggregateId, baseVersion, baseValue, instructionId: selectedInstruction, fieldId, attachmentIds, correctionReason: correctionReason || undefined, changes: { field, workType, startedAt, endedAt, memo, attachmentIds } }, fieldGroupId)) navigate("today");
  };
  const addPhoto = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!["image/jpeg", "image/png", "image/webp", "image/heic"].includes(file.type) || file.size > 10 * 1024 * 1024) {
      setNotice(tr("app.l358.101")); return;
    }
    const id = eventId();
    await storage.saveAttachment({ id, tenantId: authorization.context.tenantId, journalId: draft.aggregateId, fileName: file.name, capturedAt: new Date(file.lastModified || Date.now()).toISOString(), blob: file, ready: false });
    setDraft((current) => ({ ...current, attachmentIds: [...(current.attachmentIds || []), id], updatedAt: new Date().toISOString() }));
    setPhotoNames((current) => [...current, file.name]);
    setNotice(tr("app.l364.102"));
    event.target.value = "";
  };

  return <div className="page-content narrow-page">
    <PageBack onBack={() => navigate("today")} />
    <div className="form-heading"><span className="section-kicker">WORK JOURNAL</span><h1>{tr("app.l370.103")}</h1><p>{tr("app.l370.104")}</p></div>
    <form className="record-form" onSubmit={submit}>
      <div className="autosave-line"><span className="status-dot"/>{tr("app.l372.105")}</div>
      <fieldset><legend>{tr("app.l373.106")}</legend><div className="form-grid">
        <label>{tr("app.l374.107")}<input value={draft.field} onChange={(event) => update("field", event.target.value)} required /></label>
        <label>{tr("app.l375.108")}<input value={draft.workType} onChange={(event) => update("workType", event.target.value)} required /></label>
      </div><div className="template-row"><span>{tr("app.l376.109")}</span>{templates.map((template) => <button key={template.id} type="button" onClick={() => setDraft((current) => ({ ...current, ...template.defaults, workType: template.workType, updatedAt: new Date().toISOString() }))}>{template.name}</button>)}</div></fieldset>
      <fieldset><legend>{tr("app.l377.110")}</legend>{bootstrap?.punchSuggestion.warning && <p className="field-warning" role="status">{bootstrap.punchSuggestion.warning === "missing_start" ? tr("app.l377.111") : tr("app.l377.112")}</p>}<div className="form-grid time-grid"><label>{tr("app.l377.113")}<input type="time" value={draft.startedAt} onChange={(event) => update("startedAt", event.target.value)} required/></label><label>{tr("app.l377.114")}<input type="time" value={draft.endedAt} onChange={(event) => update("endedAt", event.target.value)} required/></label><div className="duration"><span>{tr("app.l377.115")}</span><strong>{duration}</strong></div></div></fieldset>
      <fieldset><legend>{tr("app.l378.116")}</legend><label>{tr("app.l378.117")}<textarea rows={4} value={draft.memo} onChange={(event) => update("memo", event.target.value)} placeholder={tr("app.l378.118")}/></label><label className="secondary-action attachment-picker">{tr("app.l378.119")}<input type="file" accept="image/jpeg,image/png,image/webp,image/heic" onChange={(event) => void addPhoto(event)}/></label>{photoNames.length > 0 && <ul className="attachment-list">{photoNames.map((name, index) => <li key={`${name}-${index}`}>{name}</li>)}</ul>}</fieldset>
      {journalId && <fieldset><legend>{tr("app.l379.120")}</legend><label>{tr("app.l379.121")}<textarea rows={3} value={correctionReason} onChange={(event) => setCorrectionReason(event.target.value)} required placeholder={tr("app.l379.122")}/></label></fieldset>}
      <div className="form-actions"><button className="secondary-action" type="button" onClick={saveDraft}>{tr("app.l380.123")}</button><button className="primary-action" type="submit">{tr("app.l380.124")}</button></div>
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
    }).catch(() => setNotice(tr("app.l402.125")));
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
    }).catch(() => setNotice(tr("app.l414.126")));
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
    <div className="form-heading"><span className="section-kicker">PESTICIDE RECORD</span><h1>{tr("app.l436.127")}</h1><p>{tr("app.l436.128")}</p></div>
    <form className="record-form" onSubmit={(event) => void submit(event)}>
      <div className={`freshness ${decision.reasons.includes("master_stale") || decision.reasons.includes("master_missing") ? "is-warning" : ""}`}><Icon name="sync"/><div><strong>{bootstrap?.release ? tr("app.l438.129", [bootstrap.release.version]) : tr("app.l438.130")}</strong><span>{bootstrap?.release ? tr("app.l438.131", [formatDate(bootstrap.release.validUntil, { dateStyle: "medium", timeStyle: "short" }), formatDate(bootstrap.release.syncedAt, { dateStyle: "medium", timeStyle: "short" })]) : tr("app.l438.132")}</span></div></div>
      <fieldset><legend>{tr("app.l439.133")}</legend><div className="form-grid"><label>{tr("app.l439.134")}<select value={fieldId} onChange={(event) => { setFieldId(event.target.value); setBootstrap(null); setChemicalId(""); setAcknowledged(false); }} required><option value="">{tr("app.l439.135")}</option>{fields.map((field) => <option key={field.id} value={field.id}>{field.properties.name}</option>)}</select></label><label>{tr("app.l439.136")}<input value={bootstrap?.field.cropName || ""} readOnly required/></label></div></fieldset>
      <fieldset><legend>{tr("app.l440.137")}</legend><label>{tr("app.l440.138")}<select value={chemicalId} onChange={(event) => { setChemicalId(event.target.value); setAcknowledged(false); }} required><option value="">{tr("app.l440.139")}</option>{bootstrap?.chemicals.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><div className="form-grid"><label>{tr("app.l440.140")}<input inputMode="numeric" type="number" value={dilution} onChange={(event) => { setDilution(Number(event.target.value)); setAcknowledged(false); }} required/><span className="field-suffix">{tr("app.l440.141")}</span></label><label>{tr("app.l440.142")}<input name="amount" inputMode="decimal" type="number" min="0.01" step="0.01" defaultValue="120" required/><span className="field-suffix">L</span></label></div></fieldset>
      <div className={`safety-result ${isWarning ? "is-warning" : "is-safe"}`} role={isWarning ? "alert" : "status"}><Icon name={isWarning ? "warning" : "leaf"}/><div><strong>{decision.status === "blocked" ? tr("app.l441.143") : isWarning ? tr("app.l441.144") : tr("app.l441.145")}</strong>{decision.reasons.length ? <ul>{decision.reasons.map((reason) => <li key={reason}>{safetyReasonLabel(reason)}</li>)}</ul> : <p>{tr("app.l441.146")}</p>}{decision.requiresManagerOverride && !managerOverride && <p>{tr("app.l441.147")}</p>}</div></div>
      {decision.status === "warning" && <label className="check-row"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)}/><span>{decision.requiresManagerOverride ? tr("app.l442.148") : tr("app.l442.149")}</span></label>}
      <fieldset><legend>{tr("app.l443.150")}</legend><div className="form-grid"><label>{tr("app.l443.151")}<input name="targetPest" defaultValue={tr("app.l443.152")} required/></label><label>{tr("app.l443.153")}<input name="equipment" defaultValue={tr("app.l443.154")} required/></label><label>{tr("app.l443.155")}<input name="workerName" defaultValue={authorization.user.displayName} required/></label><label>{tr("app.l443.156")}<input type="date" value={appliedOn} onChange={(event) => { setAppliedOn(event.target.value); setAcknowledged(false); }} required/></label><label>{tr("app.l443.157")}<input type="date" value={plannedHarvestOn} onChange={(event) => { setPlannedHarvestOn(event.target.value); setAcknowledged(false); }}/></label></div></fieldset>
      <div className="form-actions"><button className="secondary-action" type="button" onClick={() => navigate("today")}>{tr("app.l444.158")}</button><button className="primary-action" type="submit" disabled={!canSubmit}>{tr("app.l444.159")}</button></div>
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
    if (online) api.getInventory(authorization.context.contextId).then(async (current) => { await storage.saveInventory(tenantId, current); if (active) setSnapshot(current); }).catch(() => setNotice(tr("app.l456.160")));
    return () => { active = false; };
  }, [api, authorization.context.contextId, authorization.context.tenantId, online, setNotice, storage]);
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = Object.fromEntries(new FormData(form));
    if (await queue("stock", { aggregateId: eventId(), chemicalId: data.chemicalId, eventType: data.eventType, quantity: Number(data.quantity), reason: data.reason })) { form.reset(); navigate("today"); }
  };
  const adjust = async (alert: NonNullable<QueueSnapshot["stockAlerts"]>[number]) => {
    const balance = snapshot.balances.find((item) => item.chemicalId === alert.chemicalId)?.quantity || 0;
    const actual = Number(physical[alert.id]);
    if (!Number.isFinite(actual)) { setNotice(tr("app.l466.161")); return; }
    if (await queue("stock", { aggregateId: eventId(), chemicalId: alert.chemicalId, eventType: "adjustment", quantity: actual - balance, reason: tr("app.l467.162", [actual]), alertId: alert.id })) setNotice(tr("app.l467.163"));
  };
  return <div className="page-content narrow-page"><PageBack onBack={() => navigate("today")}/><div className="form-heading"><span className="section-kicker">INVENTORY</span><h1>{tr("app.l469.164")}</h1><p>{tr("app.l469.165")}</p></div>
    <section className="queue-panel"><h2>{tr("app.l470.166")}</h2>{snapshot.balances.length ? snapshot.balances.map((item) => <article key={item.chemicalId}><strong>{item.name}</strong><p>{item.registrationNumber}{tr("app.l470.167")} <b>{item.quantity}</b>{item.policy ? tr("app.l470.168", [item.policy.reorderPoint, item.policy.targetLevel]) : ""}</p>{item.belowReorderPoint && <p role="alert">{tr("app.l470.169")}</p>}</article>) : <p>{tr("app.l470.170")}</p>}</section>
    {!!snapshot.incoming?.length && <section className="queue-panel"><h2>{tr("app.l471.171")}</h2>{snapshot.incoming.map((item) => <article key={item.purchaseOrderLineId || `${item.purchaseOrderId}-${item.chemicalId}`}><strong>{item.name || item.chemicalId}</strong><p>{item.orderNumber}{tr("app.l471.172")}{item.supplierName}{tr("app.l471.173")} {item.incomingQuantity}{item.unit}{tr("app.l471.174")} {item.expectedOn || tr("app.l471.175")}</p></article>)}</section>}
    {!!snapshot.lots?.length && <section className="queue-panel"><h2>{tr("app.l472.176")}</h2>{snapshot.lots.map((lot) => <article key={lot.id}><strong>{lot.name || lot.chemicalId}／{lot.lotNumber}</strong><p>{tr("app.l472.177")} {lot.quantity}{lot.unit}{tr("app.l472.178")} {lot.expiresOn || tr("app.l472.179")}{tr("app.l472.180")} {formatNumber(lot.inventoryValue)} {lot.currency}</p></article>)}</section>}
    {!!snapshot.counts?.length && <section className="queue-panel"><h2>{tr("app.l473.181")}</h2>{snapshot.counts.map((count) => <article key={count.id}><strong>{count.locationName}</strong><p>{formatDate(count.countedAt, { dateStyle: "medium", timeStyle: "short" })}{tr("app.l473.182")}{count.status}{tr("app.l473.183")} {count.absoluteVariance ?? 0}</p></article>)}</section>}
    <form className="record-form" onSubmit={(event) => void submit(event)}><fieldset><legend>{tr("app.l474.184")}</legend><div className="form-grid"><label>{tr("app.l474.185")}<select name="chemicalId" required><option value="">{tr("app.l474.186")}</option>{snapshot.balances.map((item) => <option key={item.chemicalId} value={item.chemicalId}>{item.name}</option>)}</select></label><label>{tr("app.l474.187")}<select name="eventType" defaultValue="withdrawal"><option value="withdrawal">{tr("app.l474.188")}</option><option value="receipt">{tr("app.l474.189")}</option></select></label><label>{tr("app.l474.190")}<input name="quantity" type="number" min="0.01" step="0.01" required/></label><label>{tr("app.l474.191")}<input name="reason" defaultValue={tr("app.l474.192")} required/></label></div></fieldset><button className="primary-action">{tr("app.l474.193")}</button></form>
    {canAdjust && snapshot.alerts.length > 0 && <section className="queue-panel"><h2>{tr("app.l475.194")}</h2>{snapshot.alerts.map((alert) => <article key={alert.id}><strong>{alert.name || snapshot.balances.find((item) => item.chemicalId === alert.chemicalId)?.name || tr("app.l475.195")}</strong><p>{tr("app.l475.196")} {alert.negativeQuantity}{tr("app.l475.197")}</p><div className="queue-actions"><label>{tr("app.l475.198")}<input type="number" step="0.01" value={physical[alert.id] || ""} onChange={(event) => setPhysical((current) => ({ ...current, [alert.id]: event.target.value }))}/></label><button className="primary-action" onClick={() => void adjust(alert)}>{tr("app.l475.199")}</button></div></article>)}</section>}
  </div>;
}

function PageBack({ onBack }: { onBack: () => void }) { return <button className="back-button" onClick={onBack}>{tr("app.l479.200")}</button>; }
function MorePage({ api, csrfToken, authorization, online, instructions, setInstructions, journals, setJournals, correctJournal, setNotice, theme, locale, queueCounts, queues, resolveConflict, onLogout }: { api: MvpGateway; csrfToken: string; authorization: AppAuthorization; online: boolean; instructions: WorkInstruction[]; setInstructions: React.Dispatch<React.SetStateAction<WorkInstruction[]>>; journals: JournalEntry[]; setJournals: React.Dispatch<React.SetStateAction<JournalEntry[]>>; correctJournal: (id: string) => void; setNotice: (message: string) => void; theme: Theme; locale: Locale; queueCounts: { rejections: number; conflicts: number }; queues: QueueSnapshot; resolveConflict: (id: string, choice: "server" | "device") => Promise<void>; onLogout?: () => Promise<void> }) {
  const manager = authorization.context.capabilities.includes("instruction:manage");
  const reviewer = authorization.context.capabilities.includes("journal:review");
  const migrationManager = authorization.context.capabilities.includes("migration:manage");
  const exportReader = authorization.context.capabilities.includes("export:read");
  const analyticsReader = authorization.context.capabilities.includes("analytics:read");
  const [returnReasons, setReturnReasons] = useState<Record<string, string>>({});
  const [assignees, setAssignees] = useState<Record<string, string>>({});
  const refreshJournals = async () => setJournals((await api.getJournals(authorization.context.contextId)).journals);
  const review = async (journal: JournalEntry, action: "approve" | "return") => {
    const reason = returnReasons[journal.id]?.trim();
    if (action === "return" && !reason) { setNotice(tr("app.l491.201")); return; }
    await api.reviewJournal(authorization.context.contextId, csrfToken, journal.id, { action, expectedVersion: journal.version, reason });
    await refreshJournals(); setNotice(action === "approve" ? tr("app.l493.202") : tr("app.l493.203"));
  };
  const createInstruction = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = Object.fromEntries(new FormData(form));
    const created = await api.createWorkInstruction(authorization.context.contextId, csrfToken, { ...data, scheduledStart: new Date(String(data.scheduledStart)).toISOString(), scheduledEnd: new Date(String(data.scheduledEnd)).toISOString(), priority: Number(data.priority) });
    setInstructions((current) => [...current, created]); form.reset(); setNotice(tr("app.l500.204"));
  };
  const reassign = async (instruction: WorkInstruction) => {
    const assigneeUserId = assignees[instruction.id]?.trim();
    if (!assigneeUserId) { setNotice(tr("app.l504.205")); return; }
    const result = await api.reassignWorkInstruction(authorization.context.contextId, csrfToken, instruction.id, { assigneeUserId, expectedVersion: instruction.version });
    setInstructions((current) => current.map((item) => item.id === instruction.id ? { ...item, version: result.version, assignment: { id: result.assignmentId, assigneeUserId: result.assigneeUserId, version: 1 } } : item));
    setNotice(tr("app.l507.206"));
  };
  return <div className="page-content narrow-page"><div className="form-heading"><span className="section-kicker">SETTINGS</span><h1>{tr("app.l509.207")}</h1><p>{tr("app.l509.208")}</p></div><div className="settings-list"><div><span>{tr("app.l509.209")}</span><strong>{theme === "field" ? tr("app.l509.210") : theme === "dark" ? tr("app.l509.211") : tr("app.l509.212")}</strong></div><div><span>{tr("app.l509.213")}</span><strong>{localeProfiles[locale].label}{!localeProfiles[locale].reviewed && tr("app.l509.214")}</strong></div><div><span>{tr("app.l509.215")}</span><strong>{tr("app.l509.216")}</strong></div><div><span>{tr("app.l509.217")}</span><strong>{queueCounts.rejections}{tr("app.l509.218")}</strong></div><div><span>{tr("app.l509.219")}</span><strong>{queueCounts.conflicts}{tr("app.l509.220")}</strong></div></div>
    {analyticsReader && <TenantAnalyticsPanel api={api} contextId={authorization.context.contextId} online={online}/>}
    {onLogout && <section className="queue-panel"><h2>{tr("app.l511.221")}</h2><p>{tr("app.l511.222")}</p><button className="secondary-action" disabled={!online} onClick={() => void onLogout().catch((error) => setNotice(error instanceof Error ? error.message : tr("app.l511.223")))}>{tr("app.l511.224")}</button></section>}
    {manager && <section className="queue-panel"><h2>{tr("app.l512.225")}</h2><form className="record-form compact-form" onSubmit={(event) => void createInstruction(event)}><div className="form-grid"><label>{tr("app.l512.226")}<input name="fieldId" required/></label><label>{tr("app.l512.227")}<input name="assigneeUserId" required/></label><label>{tr("app.l512.228")}<input name="title" required/></label><label>{tr("app.l512.229")}<input name="workType" required/></label><label>{tr("app.l512.230")}<input name="scheduledStart" type="datetime-local" required/></label><label>{tr("app.l512.231")}<input name="scheduledEnd" type="datetime-local" required/></label><label>{tr("app.l512.232")}<select name="priority" defaultValue="1"><option value="0">{tr("app.l512.233")}</option><option value="1">{tr("app.l512.234")}</option><option value="2">{tr("app.l512.235")}</option></select></label><label>{tr("app.l512.236")}<textarea name="details" rows={2}/></label></div><button className="primary-action" disabled={!online}>{tr("app.l512.237")}</button></form>
      <div>{instructions.map((instruction) => <article key={instruction.id}><strong>{instruction.title}</strong><p>{instruction.fieldName}{tr("app.l513.238")} {instruction.assignment?.assigneeUserId || tr("app.l513.239")}{tr("app.l513.240")} {instruction.version}</p><div className="queue-actions"><label>{tr("app.l513.241")}<input value={assignees[instruction.id] || ""} onChange={(event) => setAssignees((current) => ({ ...current, [instruction.id]: event.target.value }))}/></label><button className="secondary-action" disabled={!online} onClick={() => void reassign(instruction)}>{tr("app.l513.242")}</button></div></article>)}</div></section>}
    {reviewer && <section className="queue-panel"><h2>{tr("app.l514.243")}</h2>{journals.filter((journal) => journal.status === "submitted" || journal.status === "corrected").map((journal) => <article key={journal.id}><strong>{journal.fieldName || String(journal.body.field || tr("app.l514.244"))}</strong><p>{String(journal.body.workType || "")}{tr("app.l514.245")}{String(journal.body.startedAt || "")}〜{String(journal.body.endedAt || "")}</p><p>{String(journal.body.memo || tr("app.l514.246"))}</p><label>{tr("app.l514.247")}<textarea value={returnReasons[journal.id] || ""} onChange={(event) => setReturnReasons((current) => ({ ...current, [journal.id]: event.target.value }))}/></label><div className="queue-actions"><button className="secondary-action" onClick={() => void review(journal, "return")}>{tr("app.l514.248")}</button><button className="primary-action" onClick={() => void review(journal, "approve")}>{tr("app.l514.249")}</button></div></article>)}</section>}
    {journals.some((journal) => journal.workerUserId === authorization.user.id && journal.status === "returned") && <section className="queue-panel"><h2>{tr("app.l515.250")}</h2>{journals.filter((journal) => journal.workerUserId === authorization.user.id && journal.status === "returned").map((journal) => <article key={journal.id}><strong>{journal.fieldName || tr("app.l515.251")}</strong><p>{journal.returnReason ? tr("app.l515.252", [journal.returnReason]) : tr("app.l515.253", [String(journal.body.workType || "")])}</p><button className="primary-action" onClick={() => correctJournal(journal.id)}>{tr("app.l515.254")}</button></article>)}</section>}
    {queues.rejections.length > 0 && <section className="queue-panel"><h2>{tr("app.l516.255")}</h2>{queues.rejections.map((item) => <article key={item.id}><strong>{item.reason}</strong><p>{tr("app.l516.256")} {item.bundleId}</p><p>{tr("app.l516.257")} {item.recoveryAction}</p></article>)}</section>}
    {queues.conflicts.length > 0 && <section className="queue-panel"><h2>{tr("app.l517.258")}</h2>{queues.conflicts.map((item) => <article key={item.id}><strong>{item.conflictingFields.join(", ")} {tr("app.l517.259")}</strong><p>{tr("app.l517.260")} {JSON.stringify(item.currentValue)}</p><p>{tr("app.l517.261")} {JSON.stringify(item.proposedValue)}</p><div className="queue-actions"><button className="secondary-action" onClick={() => void resolveConflict(item.id, "server")}>{tr("app.l517.262")}</button><button className="primary-action" onClick={() => void resolveConflict(item.id, "device")}>{tr("app.l517.263")}</button></div></article>)}</section>}
    {(migrationManager || exportReader) && <DataMigrationPanel api={api} contextId={authorization.context.contextId} csrfToken={csrfToken} online={online} canImport={migrationManager} canExport={exportReader} setNotice={setNotice}/>}
    {authorization.context.capabilities.some((capability) => ["security:manage", "privacy:manage", "break_glass:approve", "pesticide:manage"].includes(capability)) && <SecurityAdministrationPanel api={api} contextId={authorization.context.contextId} csrfToken={csrfToken} actorUserId={authorization.user.id} capabilities={authorization.context.capabilities} online={online} setNotice={setNotice}/>}
  </div>;
}
