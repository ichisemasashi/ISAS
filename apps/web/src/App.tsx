import { useEffect, useMemo, useState } from "react";
import { browserStorage, type JournalDraft, type StorageGateway } from "./storage";

type Route = "today" | "journal" | "pesticide" | "fields" | "more";
type PunchState = "idle" | "working" | "break";
type Theme = "field" | "dark" | "contrast";
type Locale = "ja" | "en";

const tasks = [
  { id: "t1", time: "08:30", field: "北の1号圃場", crop: "つや姫", work: "水位を確認", status: "次の作業" },
  { id: "t2", time: "10:00", field: "西のハウス", crop: "ミニトマト", work: "誘引・わき芽取り", status: "今日" },
  { id: "t3", time: "14:00", field: "南の3号圃場", crop: "雪若丸", work: "除草剤散布", status: "要安全確認" },
] as const;

const copy = {
  ja: { today: "今日", record: "記録", fields: "圃場", more: "その他", online: "オンライン", offline: "オフライン" },
  en: { today: "Today", record: "Record", fields: "Fields", more: "More", online: "Online", offline: "Offline" },
} as const;

const Icon = ({ name }: { name: "today" | "record" | "field" | "more" | "sync" | "clock" | "leaf" | "warning" }) => {
  const paths = {
    today: <><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4m8-4v4M3 10h18"/></>,
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

function eventId(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function durationLabel(startedAt: string, endedAt: string): string {
  const [startHour, startMinute] = startedAt.split(":").map(Number);
  const [endHour, endMinute] = endedAt.split(":").map(Number);
  const minutes = Math.max(0, endHour * 60 + endMinute - startHour * 60 - startMinute);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours ? `${hours}時間${rest ? `${rest}分` : ""}` : `${rest}分`;
}

export function App({ storage = browserStorage }: { storage?: StorageGateway }) {
  const [route, setRoute] = useState<Route>("today");
  const [online, setOnline] = useState(() => navigator.onLine);
  const [pending, setPending] = useState(0);
  const [punch, setPunch] = useState<PunchState>("idle");
  const [theme, setTheme] = useState<Theme>("field");
  const [locale, setLocale] = useState<Locale>("ja");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    storage.pendingCount().then(setPending).catch(() => setNotice("端末保存を確認できませんでした。もう一度お試しください。"));
    const wentOnline = () => setOnline(true);
    const wentOffline = () => setOnline(false);
    window.addEventListener("online", wentOnline);
    window.addEventListener("offline", wentOffline);
    navigator.storage?.persist?.().catch(() => undefined);
    return () => {
      window.removeEventListener("online", wentOnline);
      window.removeEventListener("offline", wentOffline);
    };
  }, [storage]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.lang = locale;
    document.documentElement.dir = "ltr";
  }, [theme, locale]);

  const navigate = (next: Route) => {
    setRoute(next);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const queue = async (kind: "journal" | "pesticide" | "punch", payload: Record<string, unknown>) => {
    await storage.enqueue({ eventUuid: eventId(kind), kind, payload, createdAt: new Date().toISOString() });
    setPending((value) => value + 1);
    setNotice(online ? "端末に保存しました。まもなく同期します。" : "端末に保存しました。電波が戻ると自動で同期します。");
  };

  const punchAction = async (next: PunchState) => {
    const action = next === "working" ? (punch === "break" ? "resume" : "start") : next === "break" ? "break" : "finish";
    await queue("punch", { action, occurredAt: new Date().toISOString() });
    setPunch(next);
  };

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">本文へ移動</a>
      <aside className="side-nav" aria-label="メインナビゲーション">
        <Brand />
        <Nav route={route} locale={locale} navigate={navigate} />
        <div className="side-foot">山形みどり農園<br/><span>現場チーム</span></div>
      </aside>

      <div className="page-shell">
        <header className="topbar">
          <div className="mobile-brand"><Brand compact /></div>
          <div className="system-status" aria-live="polite">
            <span className={`connection ${online ? "is-online" : "is-offline"}`}><span className="status-dot" />{online ? copy[locale].online : copy[locale].offline}</span>
            <span className="sync-state"><Icon name="sync" />未同期 {pending}件</span>
          </div>
          <div className="preferences">
            <button className="text-button" onClick={() => setLocale((value) => value === "ja" ? "en" : "ja")} aria-label="表示言語を切り替える">{locale === "ja" ? "EN" : "日本語"}</button>
            <button className="text-button" onClick={() => setTheme((value) => value === "field" ? "dark" : value === "dark" ? "contrast" : "field")} aria-label="表示テーマを切り替える">表示</button>
            <button className="avatar" aria-label="アカウントメニュー">佐</button>
          </div>
        </header>

        <main id="main" tabIndex={-1}>
          {route === "today" && <TodayPage punch={punch} punchAction={punchAction} navigate={navigate} />}
          {route === "journal" && <JournalPage storage={storage} queue={queue} navigate={navigate} setNotice={setNotice} />}
          {route === "pesticide" && <PesticidePage queue={queue} navigate={navigate} />}
          {route === "fields" && <PlaceholderPage title="圃場" description="担当圃場の一覧・地図は、次の縦切りでPostGIS APIへ接続します。" />}
          {route === "more" && <MorePage theme={theme} locale={locale} />}
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
  const items: { route: Route; label: string; icon: "today" | "record" | "field" | "more" }[] = [
    { route: "today", label: copy[locale].today, icon: "today" },
    { route: "journal", label: copy[locale].record, icon: "record" },
    { route: "fields", label: copy[locale].fields, icon: "field" },
    { route: "more", label: copy[locale].more, icon: "more" },
  ];
  return <div className="nav-items">{items.map((item) => <button key={item.route} className={route === item.route ? "active" : ""} aria-current={route === item.route ? "page" : undefined} onClick={() => navigate(item.route)}><Icon name={item.icon}/><span>{item.label}</span></button>)}</div>;
}

function TodayPage({ punch, punchAction, navigate }: { punch: PunchState; punchAction: (state: PunchState) => Promise<void>; navigate: (route: Route) => void }) {
  const date = new Intl.DateTimeFormat("ja-JP", { month: "long", day: "numeric", weekday: "short" }).format(new Date());
  return <div className="page-content">
    <section className="welcome-row">
      <div><p className="eyebrow">{date}</p><h1>おはようございます、佐藤さん</h1><p>今日の作業は3件です。安全確認が必要な作業があります。</p></div>
      <button className="primary-action desktop-only" onClick={() => navigate("journal")}><Icon name="record"/>日誌をつける</button>
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
        <div className="section-head"><div><span className="section-kicker">MY TASKS</span><h2 id="tasks-title">今日の作業</h2></div><button className="text-link">すべて見る</button></div>
        <div className="task-list">{tasks.map((task, index) => <article className={`task-card ${index === 2 ? "needs-check" : ""}`} key={task.id}>
          <div className="task-time"><strong>{task.time}</strong><span>{task.status}</span></div>
          <div className="task-main"><h3>{task.work}</h3><p>{task.field}<span aria-hidden="true">・</span>{task.crop}</p></div>
          {index === 2 ? <button className="task-button warning-button" onClick={() => navigate("pesticide")}><Icon name="warning"/>安全確認</button> : <button className="task-button" onClick={() => navigate("journal")}>記録する</button>}
        </article>)}</div>
      </section>

      <aside className="quick-section" aria-labelledby="quick-title">
        <div className="section-head"><div><span className="section-kicker">QUICK ACTIONS</span><h2 id="quick-title">すぐに記録</h2></div></div>
        <div className="quick-grid">
          <button onClick={() => navigate("journal")}><span className="quick-icon green"><Icon name="record"/></span><span><strong>作業日誌</strong><small>前回値から入力</small></span></button>
          <button onClick={() => navigate("pesticide")} aria-label="農薬記録を始める"><span className="quick-icon amber"><Icon name="leaf"/></span><span><strong>農薬記録</strong><small>使用基準を確認</small></span></button>
          <button onClick={() => navigate("fields")}><span className="quick-icon blue"><Icon name="field"/></span><span><strong>圃場を見る</strong><small>担当圃場を確認</small></span></button>
        </div>
        <div className="safety-note"><Icon name="leaf"/><div><strong>農薬マスタは最新です</strong><span>2026年8月14日 06:10 更新</span></div></div>
      </aside>
    </div>
  </div>;
}

function JournalPage({ storage, queue, navigate, setNotice }: { storage: StorageGateway; queue: (kind: "journal", payload: Record<string, unknown>) => Promise<void>; navigate: (route: Route) => void; setNotice: (message: string) => void }) {
  const [draft, setDraft] = useState<JournalDraft>({ id: "today-journal", field: "北の1号圃場", workType: "水管理", startedAt: "08:12", endedAt: "09:36", memo: "", updatedAt: new Date().toISOString() });
  const update = (key: keyof JournalDraft, value: string) => setDraft((current) => ({ ...current, [key]: value, updatedAt: new Date().toISOString() }));
  const duration = useMemo(() => durationLabel(draft.startedAt, draft.endedAt), [draft.startedAt, draft.endedAt]);
  useEffect(() => {
    const timer = window.setTimeout(() => storage.saveDraft(draft).catch(() => setNotice("下書きを保存できませんでした。もう一度お試しください。")), 350);
    return () => window.clearTimeout(timer);
  }, [draft, setNotice, storage]);
  const saveDraft = async () => { await storage.saveDraft(draft); setNotice("下書きを端末に保存しました。"); };
  const submit = async (event: React.FormEvent) => { event.preventDefault(); await queue("journal", draft); navigate("today"); };

  return <div className="page-content narrow-page">
    <PageBack onBack={() => navigate("today")} />
    <div className="form-heading"><span className="section-kicker">WORK JOURNAL</span><h1>作業日誌をつける</h1><p>打刻と前回値から入力済みです。違うところだけ直してください。</p></div>
    <form className="record-form" onSubmit={submit}>
      <div className="autosave-line"><span className="status-dot"/>端末へ自動保存</div>
      <fieldset><legend>作業の内容</legend><div className="form-grid">
        <label>圃場<select value={draft.field} onChange={(event) => update("field", event.target.value)}><option>北の1号圃場</option><option>西のハウス</option><option>南の3号圃場</option></select></label>
        <label>作業<select value={draft.workType} onChange={(event) => update("workType", event.target.value)}><option>水管理</option><option>草刈り</option><option>誘引</option><option>収穫</option></select></label>
      </div><div className="template-row"><span>よく使う作業</span><button type="button" onClick={() => update("workType", "水管理")}>水管理</button><button type="button" onClick={() => update("workType", "草刈り")}>草刈り</button><button type="button" onClick={() => update("workType", "収穫")}>収穫</button></div></fieldset>
      <fieldset><legend>作業した時間</legend><div className="form-grid time-grid"><label>開始<input type="time" value={draft.startedAt} onChange={(event) => update("startedAt", event.target.value)}/></label><label>終了<input type="time" value={draft.endedAt} onChange={(event) => update("endedAt", event.target.value)}/></label><div className="duration"><span>作業時間</span><strong>{duration}</strong></div></div></fieldset>
      <fieldset><legend>メモ・写真</legend><label>作業メモ<textarea rows={4} value={draft.memo} onChange={(event) => update("memo", event.target.value)} placeholder="気づいたことを入力（任意）"/></label><button className="secondary-action" type="button">写真を追加</button></fieldset>
      <div className="form-actions"><button className="secondary-action" type="button" onClick={saveDraft}>下書き保存</button><button className="primary-action" type="submit">この内容で記録</button></div>
    </form>
  </div>;
}

function PesticidePage({ queue, navigate }: { queue: (kind: "pesticide", payload: Record<string, unknown>) => Promise<void>; navigate: (route: Route) => void }) {
  const [chemical, setChemical] = useState("グリーンフロアブル");
  const [acknowledged, setAcknowledged] = useState(false);
  const warning = chemical === "テスト乳剤（要確認）";
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = Object.fromEntries(new FormData(event.currentTarget));
    await queue("pesticide", { ...data, safetyCacheVersion: "2026.08.14-1", warningAcknowledged: acknowledged });
    navigate("today");
  };
  return <div className="page-content narrow-page">
    <PageBack onBack={() => navigate("today")} />
    <div className="form-heading"><span className="section-kicker">PESTICIDE RECORD</span><h1>農薬記録</h1><p>散布前に使用基準を確認します。警告は色だけでなく文章でも表示します。</p></div>
    <form className="record-form" onSubmit={submit}>
      <div className="freshness"><Icon name="sync"/><div><strong>安全基準データは最新です</strong><span>2026年8月14日 06:10更新・オフライン確認可</span></div></div>
      <fieldset><legend>散布する場所と作物</legend><div className="form-grid"><label>圃場<select name="field" defaultValue="南の3号圃場"><option>南の3号圃場</option><option>北の1号圃場</option></select></label><label>作物<select name="crop" defaultValue="雪若丸"><option>雪若丸</option><option>つや姫</option></select></label></div></fieldset>
      <fieldset><legend>薬剤と使用量</legend><label>薬剤名<select name="chemical" value={chemical} onChange={(event) => { setChemical(event.target.value); setAcknowledged(false); }}><option>グリーンフロアブル</option><option>テスト乳剤（要確認）</option></select></label><div className="form-grid"><label>希釈倍率<input name="dilution" inputMode="numeric" defaultValue="1000"/><span className="field-suffix">倍</span></label><label>散布量<input name="amount" inputMode="decimal" defaultValue="120"/><span className="field-suffix">L</span></label></div></fieldset>
      <div className={`safety-result ${warning ? "is-warning" : "is-safe"}`} role={warning ? "alert" : "status"}><Icon name={warning ? "warning" : "leaf"}/><div><strong>{warning ? "使用回数を確認してください" : "使用基準の範囲内です"}</strong><p>{warning ? "今作の使用回数が上限に達する可能性があります。履歴を確認し、責任者の判断を記録してください。" : "適用作物・希釈倍率・使用回数・収穫前日数を確認しました。"}</p></div></div>
      {warning && <label className="check-row"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)}/><span>警告内容と使用履歴を確認しました</span></label>}
      <fieldset><legend>記録に必要な情報</legend><div className="form-grid"><label>対象病害虫<input name="target" defaultValue="ノビエ"/></label><label>使用器具<input name="equipment" defaultValue="背負式噴霧器"/></label><label>作業者<input name="worker" defaultValue="佐藤 一郎"/></label><label>散布日<input name="date" type="date" defaultValue="2026-08-14"/></label></div></fieldset>
      <div className="form-actions"><button className="secondary-action" type="button" onClick={() => navigate("today")}>戻る</button><button className="primary-action" type="submit" disabled={warning && !acknowledged}>安全確認して記録</button></div>
    </form>
  </div>;
}

function PageBack({ onBack }: { onBack: () => void }) { return <button className="back-button" onClick={onBack}>← 今日の作業へ戻る</button>; }
function PlaceholderPage({ title, description }: { title: string; description: string }) { return <div className="page-content empty-page"><span className="empty-icon"><Icon name="field"/></span><h1>{title}</h1><p>{description}</p><button className="secondary-action">実装バックログを見る</button></div>; }
function MorePage({ theme, locale }: { theme: Theme; locale: Locale }) { return <div className="page-content narrow-page"><div className="form-heading"><span className="section-kicker">SETTINGS</span><h1>その他</h1><p>表示と端末状態を確認できます。</p></div><div className="settings-list"><div><span>表示テーマ</span><strong>{theme === "field" ? "屋外向け" : theme === "dark" ? "ダーク" : "高コントラスト"}</strong></div><div><span>表示言語</span><strong>{locale === "ja" ? "日本語" : "English"}</strong></div><div><span>オフライン保持</span><strong>利用可能</strong></div></div></div>; }
