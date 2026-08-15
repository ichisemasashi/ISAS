import { useCallback, useEffect, useState } from "react";
import type { MvpGateway, PesticideMasterReview, PrivacyRequest, SecuritySnapshot } from "./api";

type Props = { api: MvpGateway; contextId: string; csrfToken: string; actorUserId: string; capabilities: string[]; online: boolean; setNotice: (message: string) => void };
const emptySnapshot: SecuritySnapshot = { users: [], roles: [], changeRequests: [], breakGlassGrants: [], privacyRequests: [] };
const localIso = (value: FormDataEntryValue | null) => value ? new Date(String(value)).toISOString() : null;
const splitIds = (value: FormDataEntryValue | null) => String(value || "").split(",").map((item) => item.trim()).filter(Boolean);

export function SecurityAdministrationPanel({ api, contextId, csrfToken, actorUserId, capabilities, online, setNotice }: Props) {
  const canSecurity = capabilities.includes("security:manage");
  const canPrivacy = capabilities.includes("privacy:manage");
  const canBreakGlass = capabilities.includes("break_glass:approve") || canSecurity;
  const canPesticide = capabilities.includes("pesticide:manage");
  const [snapshot, setSnapshot] = useState(emptySnapshot);
  const [reviews, setReviews] = useState<PesticideMasterReview[]>([]);
  const [decisionNotes, setDecisionNotes] = useState<Record<string, string>>({});

  const refresh = useCallback(async () => {
    const [security, pesticide] = await Promise.all([
      canSecurity || canPrivacy || canBreakGlass ? api.getSecurityAdministration(contextId) : Promise.resolve(emptySnapshot),
      canPesticide ? api.getPesticideMasterReviews(contextId) : Promise.resolve({ reviews: [] }),
    ]);
    setSnapshot(security); setReviews(pesticide.reviews);
  }, [api, contextId, canSecurity, canPrivacy, canBreakGlass, canPesticide]);
  useEffect(() => { if (online) void refresh().catch(() => setNotice("管理データを取得できませんでした。再認証または通信状態を確認してください。")); }, [online, refresh, setNotice]);

  const requestUserChange = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    const changeType = String(data.get("changeType"));
    const proposedState = changeType === "user_revoke" ? {} : {
      ...(changeType === "user_register" ? { issuer: data.get("issuer"), subject: data.get("subject") } : {}),
      displayName: data.get("displayName"), roleKey: data.get("roleKey"), membershipStatus: "active",
      fieldGroupIds: splitIds(data.get("fieldGroupIds")), validFrom: localIso(data.get("validFrom")), validUntil: localIso(data.get("validUntil")),
    };
    await api.requestSecurityChange(contextId, csrfToken, { changeType, targetUserId: data.get("targetUserId"), reason: data.get("reason"), ticketRef: data.get("ticketRef"), proposedState });
    form.reset(); await refresh(); setNotice("利用者変更を承認待ちとして登録しました。");
  };

  const requestBreakGlass = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    await api.requestSecurityChange(contextId, csrfToken, { changeType: "break_glass", targetUserId: data.get("targetUserId"), reason: data.get("reason"), ticketRef: data.get("ticketRef"), proposedState: { grantId: crypto.randomUUID(), capabilities: splitIds(data.get("capabilities")), validUntil: localIso(data.get("validUntil")) } });
    form.reset(); await refresh(); setNotice("break-glass権限を二人承認へ回付しました。");
  };

  const decideChange = async (requestId: string, decision: "approve" | "reject") => {
    const note = decisionNotes[requestId]?.trim(); if (!note) { setNotice("判断根拠を入力してください。"); return; }
    await api.decideSecurityChange(contextId, csrfToken, requestId, { decision, note }); await refresh(); setNotice(decision === "approve" ? "別の管理者として承認し、変更を実行しました。" : "申請を却下しました。");
  };

  const createPrivacy = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    let details: Record<string, unknown>; try { details = JSON.parse(String(data.get("details"))); } catch { setNotice("対象データを正しいJSONで入力してください。"); return; }
    await api.createPrivacyRequest(contextId, csrfToken, { subjectUserId: data.get("subjectUserId") || null, requestType: data.get("requestType"), dueAt: localIso(data.get("dueAt")), note: data.get("note"), details });
    form.reset(); await refresh(); setNotice("Privacy requestを受付し、承認待ちにしました。");
  };

  const privacyAction = async (request: PrivacyRequest, action: string) => {
    const key = `privacy-${request.requestId}`; const note = decisionNotes[key]?.trim(); if (!note) { setNotice("処理内容または判断根拠を入力してください。"); return; }
    await api.transitionPrivacyRequest(contextId, csrfToken, request.requestId, { action, note, ...(action === "complete" ? { evidenceRef: decisionNotes[`${key}-evidence`]?.trim() } : {}) }); await refresh(); setNotice("Privacy requestの状態を更新しました。");
  };

  const requestPesticide = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    let release: Record<string, unknown>; try { release = JSON.parse(String(data.get("release"))); } catch { setNotice("公開候補を正しいJSONで入力してください。"); return; }
    await api.requestPesticideMasterReview(contextId, csrfToken, { release, reason: data.get("reason"), ticketRef: data.get("ticketRef") }); form.reset(); await refresh(); setNotice("農薬masterをreview待ちとして登録しました。");
  };

  const decidePesticide = async (id: string, decision: "approve" | "reject") => {
    const note = decisionNotes[`pesticide-${id}`]?.trim(); if (!note) { setNotice("review所見を入力してください。"); return; }
    await api.decidePesticideMasterReview(contextId, csrfToken, id, { decision, note }); await refresh(); setNotice(decision === "approve" ? "農薬masterを公開しました。" : "農薬master案を却下しました。");
  };

  return <section className="security-admin" aria-labelledby="security-admin-title"><div className="panel-heading"><span className="section-kicker">SECURITY ADMIN</span><h2 id="security-admin-title">管理者向けセキュリティ操作</h2><p>すべての変更は理由・ticket・変更前後を記録し、申請者とは別の管理者が承認します。</p></div>
    {!online && <p role="status" className="warning-box">管理操作はオフラインでは実行できません。</p>}
    {canSecurity && <section className="admin-subpanel"><h3>利用者・tenant権限</h3><form className="record-form compact-form" onSubmit={(event) => void requestUserChange(event)}><div className="form-grid"><label>操作<select name="changeType" defaultValue="user_change"><option value="user_register">利用者登録</option><option value="user_change">利用者変更</option><option value="user_revoke">利用者失効</option></select></label><label>利用者ID<input name="targetUserId" required placeholder="UUID"/></label><label>OIDC issuer<input name="issuer" placeholder="登録時に入力"/></label><label>OIDC subject<input name="subject" placeholder="登録時に入力"/></label><label>表示名<input name="displayName"/></label><label>role<select name="roleKey">{snapshot.roles.map((role) => <option key={role.roleKey} value={role.roleKey}>{role.roleLabel} ({role.roleKey})</option>)}</select></label><label>field-group scope<input name="fieldGroupIds" placeholder="UUIDをカンマ区切り"/></label><label>利用開始<input name="validFrom" type="datetime-local"/></label><label>利用期限<input name="validUntil" type="datetime-local"/></label><label>ticket<input name="ticketRef" required placeholder="SEC-123"/></label><label className="wide-field">申請理由<input name="reason" minLength={10} required/></label></div><button className="primary-action" disabled={!online}>二人承認へ申請</button></form>
      <h4>現在の利用者</h4><div className="admin-table-wrap"><table><thead><tr><th>利用者</th><th>role / scope</th><th>状態・期限</th><th>権限version</th></tr></thead><tbody>{snapshot.users.map((user) => <tr key={user.userId}><td><strong>{user.displayName}</strong><small>{user.userId}</small></td><td>{user.roleKey}<small>{user.fieldGroupIds.length ? user.fieldGroupIds.join(", ") : "tenant全体"}</small></td><td>{user.membershipStatus}<small>{user.validUntil || "期限なし"}</small></td><td>{user.authorizationVersion}</td></tr>)}</tbody></table></div></section>}
    {canBreakGlass && <section className="admin-subpanel"><h3>break-glass権限</h3><p>5分超・最大1時間の必要最小限の権限だけを発行できます。</p><form className="record-form compact-form" onSubmit={(event) => void requestBreakGlass(event)}><div className="form-grid"><label>対象利用者ID<input name="targetUserId" required/></label><label>一時権限<input name="capabilities" required placeholder="conflict:resolve, security:manage"/></label><label>失効日時<input name="validUntil" type="datetime-local" required/></label><label>incident ticket<input name="ticketRef" required/></label><label className="wide-field">緊急理由<input name="reason" minLength={10} required/></label></div><button className="danger-action" disabled={!online}>期限付き発行を申請</button></form></section>}
    {(canSecurity || canBreakGlass) && <section className="admin-subpanel"><h3>二人承認・変更前後の監査</h3>{snapshot.changeRequests.length === 0 ? <p>申請はありません。</p> : snapshot.changeRequests.map((request) => <article key={request.requestId} className="audit-card"><header><strong>{request.changeType}</strong><span className={`status-chip ${request.status}`}>{request.status}</span></header><p>対象 {request.targetUserId}・申請者 {request.requestedBy}・ticket {request.ticketRef}</p><p>{request.reason}</p><details><summary>変更前後を比較</summary><div className="audit-diff"><div><b>変更前</b><pre>{JSON.stringify(request.beforeState, null, 2)}</pre></div><div><b>変更後</b><pre>{JSON.stringify(request.proposedState, null, 2)}</pre></div></div></details>{request.status === "pending" && <div className="decision-box"><label>判断根拠<textarea value={decisionNotes[request.requestId] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [request.requestId]: event.target.value }))}/></label><div className="queue-actions"><button className="secondary-action" disabled={!online || request.requestedBy === actorUserId} onClick={() => void decideChange(request.requestId, "reject")}>却下</button><button className="primary-action" disabled={!online || request.requestedBy === actorUserId} onClick={() => void decideChange(request.requestId, "approve")}>別管理者として承認</button></div>{request.requestedBy === actorUserId && <small>申請者本人は承認できません。</small>}</div>}</article>)}</section>}
    {canPrivacy && <section className="admin-subpanel"><h3>Privacy request</h3><form className="record-form compact-form" onSubmit={(event) => void createPrivacy(event)}><div className="form-grid"><label>種類<select name="requestType"><option value="disclosure">開示</option><option value="correction">訂正</option><option value="deletion">削除</option></select></label><label>本人の利用者ID<input name="subjectUserId"/></label><label>対応期限<input name="dueAt" type="datetime-local" required/></label><label className="wide-field">対象データ（JSON）<textarea name="details" defaultValue={'{"channels":["journal"]}'} required/></label><label className="wide-field">受付メモ<input name="note" required/></label></div><button className="primary-action" disabled={!online}>本人確認済みとして受付</button></form>{snapshot.privacyRequests.map((request) => <article key={request.requestId} className="audit-card"><header><strong>{request.requestType}</strong><span className="status-chip">{request.status}</span></header><p>期限 {request.dueAt}・対象 {request.subjectUserId || "外部本人"}</p><details><summary>処理履歴を表示</summary><ol>{request.events.map((event) => <li key={event.eventId}>{event.fromStatus || "受付"} → {event.toStatus}: {event.note}</li>)}</ol></details>{!["completed", "rejected", "blocked_legal_hold"].includes(request.status) && <div className="decision-box"><label>処理内容・判断根拠<textarea value={decisionNotes[`privacy-${request.requestId}`] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [`privacy-${request.requestId}`]: event.target.value }))}/></label>{request.status === "in_progress" && <label>完了証跡URI<input value={decisionNotes[`privacy-${request.requestId}-evidence`] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [`privacy-${request.requestId}-evidence`]: event.target.value }))}/></label>}<div className="queue-actions">{request.status === "submitted" && <><button className="secondary-action" disabled={request.requestedBy === actorUserId} onClick={() => void privacyAction(request, "reject")}>却下</button><button className="primary-action" disabled={request.requestedBy === actorUserId} onClick={() => void privacyAction(request, "approve")}>承認</button></>}{request.status === "approved" && <button className="primary-action" onClick={() => void privacyAction(request, "start")}>処理開始</button>}{["approved", "in_progress"].includes(request.status) && <button className="danger-action" onClick={() => void privacyAction(request, "block_legal_hold")}>法的保持で停止</button>}{request.status === "in_progress" && <button className="primary-action" onClick={() => void privacyAction(request, "complete")}>証跡付きで完了</button>}</div></div>}</article>)}</section>}
    {canPesticide && <section className="admin-subpanel"><h3>農薬master review・公開</h3><form className="record-form compact-form" onSubmit={(event) => void requestPesticide(event)}><label>公開候補（JSON）<textarea name="release" rows={8} defaultValue={'{"version":"jp-2026-08","validUntil":"2027-08-01T00:00:00Z","chemicals":[]}'}/></label><div className="form-grid"><label>ticket<input name="ticketRef" required/></label><label>申請理由<input name="reason" minLength={10} required/></label></div><button className="primary-action" disabled={!online}>reviewへ回付</button></form>{reviews.map((review) => <article key={review.id} className="audit-card"><header><strong>{String(review.proposedRelease.version || "農薬master案")}</strong><span className="status-chip">{review.status}</span></header><p>{review.reason}・申請者 {review.requestedBy}</p><details><summary>公開前後を比較</summary><div className="audit-diff"><pre>{JSON.stringify(review.beforeRelease, null, 2)}</pre><pre>{JSON.stringify(review.proposedRelease, null, 2)}</pre></div></details>{review.status === "pending" && <div className="decision-box"><label>review所見<textarea value={decisionNotes[`pesticide-${review.id}`] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [`pesticide-${review.id}`]: event.target.value }))}/></label><div className="queue-actions"><button className="secondary-action" disabled={review.requestedBy === actorUserId} onClick={() => void decidePesticide(review.id, "reject")}>却下</button><button className="primary-action" disabled={review.requestedBy === actorUserId} onClick={() => void decidePesticide(review.id, "approve")}>review承認して公開</button></div>{review.requestedBy === actorUserId && <small>申請者本人は公開承認できません。</small>}</div>}</article>)}</section>}
  </section>;
}
