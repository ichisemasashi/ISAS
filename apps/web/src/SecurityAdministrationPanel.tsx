import { useCallback, useEffect, useState } from "react";
import { ApiProblem, type LocalTestUserCredential, type MvpGateway, type PesticideMasterReview, type PrivacyRequest, type SecuritySnapshot } from "./api";
import { tr } from "./i18n";

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
  const [localCredential, setLocalCredential] = useState<LocalTestUserCredential | null>(null);
  const [stepUpUrl, setStepUpUrl] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const [security, pesticide] = await Promise.all([
      canSecurity || canPrivacy || canBreakGlass ? api.getSecurityAdministration(contextId) : Promise.resolve(emptySnapshot),
      canPesticide ? api.getPesticideMasterReviews(contextId) : Promise.resolve({ reviews: [] }),
    ]);
    setSnapshot(security); setReviews(pesticide.reviews); setStepUpUrl(null);
  }, [api, contextId, canSecurity, canPrivacy, canBreakGlass, canPesticide]);
  useEffect(() => { if (online) void refresh().catch((error) => {
    if (error instanceof ApiProblem && error.type === "step_up_required" && typeof error.body.stepUpUrl === "string") setStepUpUrl(error.body.stepUpUrl);
    setNotice(tr("securityadministrationpanel.l25.1"));
  }); }, [online, refresh, setNotice]);

  const requestUserChange = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    const changeType = String(data.get("changeType"));
    const proposedState = changeType === "user_revoke" ? {} : {
      ...(changeType === "user_register" ? { issuer: data.get("issuer"), subject: data.get("subject") } : {}),
      displayName: data.get("displayName"), roleKey: data.get("roleKey"), membershipStatus: "active",
      fieldGroupIds: splitIds(data.get("fieldGroupIds")), validFrom: localIso(data.get("validFrom")), validUntil: localIso(data.get("validUntil")),
    };
    await api.requestSecurityChange(contextId, csrfToken, { changeType, targetUserId: data.get("targetUserId"), reason: data.get("reason"), ticketRef: data.get("ticketRef"), proposedState });
    form.reset(); await refresh(); setNotice(tr("securityadministrationpanel.l36.2"));
  };

  const provisionLocalTestUser = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    if (!api.provisionLocalTestUser) return;
    try {
      const credential = await api.provisionLocalTestUser(contextId, csrfToken, {
        username: String(data.get("username") || ""), displayName: String(data.get("displayName") || ""), roleKey: String(data.get("roleKey") || "worker"),
      });
      setLocalCredential(credential); form.reset(); await refresh(); setNotice(tr("securityadministrationpanel.local.created"));
    } catch (error) {
      setNotice(error instanceof Error ? error.message : tr("securityadministrationpanel.local.failed"));
    }
  };

  const requestBreakGlass = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    await api.requestSecurityChange(contextId, csrfToken, { changeType: "break_glass", targetUserId: data.get("targetUserId"), reason: data.get("reason"), ticketRef: data.get("ticketRef"), proposedState: { grantId: crypto.randomUUID(), capabilities: splitIds(data.get("capabilities")), validUntil: localIso(data.get("validUntil")) } });
    form.reset(); await refresh(); setNotice(tr("securityadministrationpanel.l42.3"));
  };

  const decideChange = async (requestId: string, decision: "approve" | "reject") => {
    const note = decisionNotes[requestId]?.trim(); if (!note) { setNotice(tr("securityadministrationpanel.l46.4")); return; }
    await api.decideSecurityChange(contextId, csrfToken, requestId, { decision, note }); await refresh(); setNotice(decision === "approve" ? tr("securityadministrationpanel.l47.5") : tr("securityadministrationpanel.l47.6"));
  };

  const createPrivacy = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    let details: Record<string, unknown>; try { details = JSON.parse(String(data.get("details"))); } catch { setNotice(tr("securityadministrationpanel.l52.7")); return; }
    await api.createPrivacyRequest(contextId, csrfToken, { subjectUserId: data.get("subjectUserId") || null, requestType: data.get("requestType"), dueAt: localIso(data.get("dueAt")), note: data.get("note"), details });
    form.reset(); await refresh(); setNotice(tr("securityadministrationpanel.l54.8"));
  };

  const privacyAction = async (request: PrivacyRequest, action: string) => {
    const key = `privacy-${request.requestId}`; const note = decisionNotes[key]?.trim(); if (!note) { setNotice(tr("securityadministrationpanel.l58.9")); return; }
    await api.transitionPrivacyRequest(contextId, csrfToken, request.requestId, { action, note, ...(action === "complete" ? { evidenceRef: decisionNotes[`${key}-evidence`]?.trim() } : {}) }); await refresh(); setNotice(tr("securityadministrationpanel.l59.10"));
  };

  const requestPesticide = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    let release: Record<string, unknown>; try { release = JSON.parse(String(data.get("release"))); } catch { setNotice(tr("securityadministrationpanel.l64.11")); return; }
    await api.requestPesticideMasterReview(contextId, csrfToken, { release, reason: data.get("reason"), ticketRef: data.get("ticketRef") }); form.reset(); await refresh(); setNotice(tr("securityadministrationpanel.l65.12"));
  };

  const decidePesticide = async (id: string, decision: "approve" | "reject") => {
    const note = decisionNotes[`pesticide-${id}`]?.trim(); if (!note) { setNotice(tr("securityadministrationpanel.l69.13")); return; }
    await api.decidePesticideMasterReview(contextId, csrfToken, id, { decision, note }); await refresh(); setNotice(decision === "approve" ? tr("securityadministrationpanel.l70.14") : tr("securityadministrationpanel.l70.15"));
  };

  const reconcileAttachments = async () => {
    const result = await api.reconcileAttachmentStorage(contextId, csrfToken);
    setNotice(tr("securityadministrationpanel.l75.16", [result.scanned, result.taggedOrphans, result.finalized, result.quarantined]));
  };

  return <section className="security-admin" aria-labelledby="security-admin-title"><div className="panel-heading"><span className="section-kicker">SECURITY ADMIN</span><h2 id="security-admin-title">{tr("securityadministrationpanel.l78.17")}</h2><p>{tr("securityadministrationpanel.l78.18")}</p></div>
    {!online && <p role="status" className="warning-box">{tr("securityadministrationpanel.l79.19")}</p>}
    {stepUpUrl && <p role="status" className="warning-box">{tr("securityadministrationpanel.local.step_up_required")} <a className="primary-action" href={stepUpUrl}>{tr("securityadministrationpanel.local.step_up")}</a></p>}
    {canSecurity && <section className="admin-subpanel"><h3>{tr("securityadministrationpanel.l80.20")}</h3>{snapshot.localTestUserRegistration && api.provisionLocalTestUser && <div className="local-test-user-registration"><h4>{tr("securityadministrationpanel.local.title")}</h4><p className="warning-box">{tr("securityadministrationpanel.local.boundary")}</p><form className="record-form compact-form" onSubmit={(event) => void provisionLocalTestUser(event)}><div className="form-grid"><label>{tr("securityadministrationpanel.local.username")}<input name="username" required pattern="[a-z][a-z0-9._-]{2,63}" placeholder="web-test-worker" autoComplete="off"/></label><label>{tr("securityadministrationpanel.local.display_name")}<input name="displayName" required maxLength={200}/></label><label>{tr("securityadministrationpanel.local.role")}<select name="roleKey" defaultValue="worker">{snapshot.roles.map((role) => <option key={role.roleKey} value={role.roleKey}>{role.roleLabel} ({role.roleKey})</option>)}</select></label></div><button className="primary-action" disabled={!online}>{tr("securityadministrationpanel.local.submit")}</button></form>{localCredential && <div role="status" className="success-box local-credential"><h4>{tr("securityadministrationpanel.local.once")}</h4><dl><dt>{tr("securityadministrationpanel.local.username")}</dt><dd><code>{localCredential.username}</code></dd><dt>{tr("securityadministrationpanel.local.temporary_password")}</dt><dd><code>{localCredential.temporaryPassword}</code></dd></dl><p>{tr("securityadministrationpanel.local.first_login")}</p><button type="button" className="secondary-action" onClick={() => setLocalCredential(null)}>{tr("securityadministrationpanel.local.close")}</button></div>}</div>}<form className="record-form compact-form" onSubmit={(event) => void requestUserChange(event)}><div className="form-grid"><label>{tr("securityadministrationpanel.l80.21")}<select name="changeType" defaultValue="user_change"><option value="user_register">{tr("securityadministrationpanel.l80.22")}</option><option value="user_change">{tr("securityadministrationpanel.l80.23")}</option><option value="user_revoke">{tr("securityadministrationpanel.l80.24")}</option></select></label><label>{tr("securityadministrationpanel.l80.25")}<input name="targetUserId" required placeholder="UUID"/></label><label>OIDC issuer<input name="issuer" placeholder={tr("securityadministrationpanel.l80.26")}/></label><label>OIDC subject<input name="subject" placeholder={tr("securityadministrationpanel.l80.27")}/></label><label>{tr("securityadministrationpanel.l80.28")}<input name="displayName"/></label><label>role<select name="roleKey">{snapshot.roles.map((role) => <option key={role.roleKey} value={role.roleKey}>{role.roleLabel} ({role.roleKey})</option>)}</select></label><label>field-group scope<input name="fieldGroupIds" placeholder={tr("securityadministrationpanel.l80.29")}/></label><label>{tr("securityadministrationpanel.l80.30")}<input name="validFrom" type="datetime-local"/></label><label>{tr("securityadministrationpanel.l80.31")}<input name="validUntil" type="datetime-local"/></label><label>ticket<input name="ticketRef" required placeholder="SEC-123"/></label><label className="wide-field">{tr("securityadministrationpanel.l80.32")}<input name="reason" minLength={10} required/></label></div><button className="primary-action" disabled={!online}>{tr("securityadministrationpanel.l80.33")}</button></form>
      <h4>{tr("securityadministrationpanel.l81.34")}</h4><p>{tr("securityadministrationpanel.l81.35")}</p><button className="secondary-action" disabled={!online} onClick={() => void reconcileAttachments()}>{tr("securityadministrationpanel.l81.36")}</button>
      <h4>{tr("securityadministrationpanel.l82.37")}</h4><div className="admin-table-wrap"><table><thead><tr><th>{tr("securityadministrationpanel.l82.38")}</th><th>role / scope</th><th>{tr("securityadministrationpanel.l82.39")}</th><th>{tr("securityadministrationpanel.l82.40")}</th></tr></thead><tbody>{snapshot.users.map((user) => <tr key={user.userId}><td><strong>{user.displayName}</strong><small>{user.userId}</small></td><td>{user.roleKey}<small>{user.fieldGroupIds.length ? user.fieldGroupIds.join(", ") : tr("securityadministrationpanel.l82.41")}</small></td><td>{user.membershipStatus}<small>{user.validUntil || tr("securityadministrationpanel.l82.42")}</small></td><td>{user.authorizationVersion}</td></tr>)}</tbody></table></div></section>}
    {canBreakGlass && <section className="admin-subpanel"><h3>{tr("securityadministrationpanel.l83.43")}</h3><p>{tr("securityadministrationpanel.l83.44")}</p><form className="record-form compact-form" onSubmit={(event) => void requestBreakGlass(event)}><div className="form-grid"><label>{tr("securityadministrationpanel.l83.45")}<input name="targetUserId" required/></label><label>{tr("securityadministrationpanel.l83.46")}<input name="capabilities" required placeholder="conflict:resolve, security:manage"/></label><label>{tr("securityadministrationpanel.l83.47")}<input name="validUntil" type="datetime-local" required/></label><label>incident ticket<input name="ticketRef" required/></label><label className="wide-field">{tr("securityadministrationpanel.l83.48")}<input name="reason" minLength={10} required/></label></div><button className="danger-action" disabled={!online}>{tr("securityadministrationpanel.l83.49")}</button></form></section>}
    {(canSecurity || canBreakGlass) && <section className="admin-subpanel"><h3>{tr("securityadministrationpanel.l84.50")}</h3>{snapshot.changeRequests.length === 0 ? <p>{tr("securityadministrationpanel.l84.51")}</p> : snapshot.changeRequests.map((request) => <article key={request.requestId} className="audit-card"><header><strong>{request.changeType}</strong><span className={`status-chip ${request.status}`}>{request.status}</span></header><p>{tr("securityadministrationpanel.l84.52")} {request.targetUserId}{tr("securityadministrationpanel.l84.53")} {request.requestedBy}{tr("securityadministrationpanel.l84.54")} {request.ticketRef}</p><p>{request.reason}</p><details><summary>{tr("securityadministrationpanel.l84.55")}</summary><div className="audit-diff"><div><b>{tr("securityadministrationpanel.l84.56")}</b><pre>{JSON.stringify(request.beforeState, null, 2)}</pre></div><div><b>{tr("securityadministrationpanel.l84.57")}</b><pre>{JSON.stringify(request.proposedState, null, 2)}</pre></div></div></details>{request.status === "pending" && <div className="decision-box"><label>{tr("securityadministrationpanel.l84.58")}<textarea value={decisionNotes[request.requestId] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [request.requestId]: event.target.value }))}/></label><div className="queue-actions"><button className="secondary-action" disabled={!online || request.requestedBy === actorUserId} onClick={() => void decideChange(request.requestId, "reject")}>{tr("securityadministrationpanel.l84.59")}</button><button className="primary-action" disabled={!online || request.requestedBy === actorUserId} onClick={() => void decideChange(request.requestId, "approve")}>{tr("securityadministrationpanel.l84.60")}</button></div>{request.requestedBy === actorUserId && <small>{tr("securityadministrationpanel.l84.61")}</small>}</div>}</article>)}</section>}
    {canPrivacy && <section className="admin-subpanel"><h3>Privacy request</h3><form className="record-form compact-form" onSubmit={(event) => void createPrivacy(event)}><div className="form-grid"><label>{tr("securityadministrationpanel.l85.62")}<select name="requestType"><option value="disclosure">{tr("securityadministrationpanel.l85.63")}</option><option value="correction">{tr("securityadministrationpanel.l85.64")}</option><option value="deletion">{tr("securityadministrationpanel.l85.65")}</option></select></label><label>{tr("securityadministrationpanel.l85.66")}<input name="subjectUserId"/></label><label>{tr("securityadministrationpanel.l85.67")}<input name="dueAt" type="datetime-local" required/></label><label className="wide-field">{tr("securityadministrationpanel.l85.68")}<textarea name="details" defaultValue={'{"channels":["journal"]}'} required/></label><label className="wide-field">{tr("securityadministrationpanel.l85.69")}<input name="note" required/></label></div><button className="primary-action" disabled={!online}>{tr("securityadministrationpanel.l85.70")}</button></form>{snapshot.privacyRequests.map((request) => <article key={request.requestId} className="audit-card"><header><strong>{request.requestType}</strong><span className="status-chip">{request.status}</span></header><p>{tr("securityadministrationpanel.l85.71")} {request.dueAt}{tr("securityadministrationpanel.l85.72")} {request.subjectUserId || tr("securityadministrationpanel.l85.73")}</p><details><summary>{tr("securityadministrationpanel.l85.74")}</summary><ol>{request.events.map((event) => <li key={event.eventId}>{event.fromStatus || tr("securityadministrationpanel.l85.75")} → {event.toStatus}: {event.note}</li>)}</ol></details>{!["completed", "rejected", "blocked_legal_hold"].includes(request.status) && <div className="decision-box"><label>{tr("securityadministrationpanel.l85.76")}<textarea value={decisionNotes[`privacy-${request.requestId}`] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [`privacy-${request.requestId}`]: event.target.value }))}/></label>{request.status === "in_progress" && <label>{tr("securityadministrationpanel.l85.77")}<input value={decisionNotes[`privacy-${request.requestId}-evidence`] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [`privacy-${request.requestId}-evidence`]: event.target.value }))}/></label>}<div className="queue-actions">{request.status === "submitted" && <><button className="secondary-action" disabled={request.requestedBy === actorUserId} onClick={() => void privacyAction(request, "reject")}>{tr("securityadministrationpanel.l85.78")}</button><button className="primary-action" disabled={request.requestedBy === actorUserId} onClick={() => void privacyAction(request, "approve")}>{tr("securityadministrationpanel.l85.79")}</button></>}{request.status === "approved" && <button className="primary-action" onClick={() => void privacyAction(request, "start")}>{tr("securityadministrationpanel.l85.80")}</button>}{["approved", "in_progress"].includes(request.status) && <button className="danger-action" onClick={() => void privacyAction(request, "block_legal_hold")}>{tr("securityadministrationpanel.l85.81")}</button>}{request.status === "in_progress" && <button className="primary-action" onClick={() => void privacyAction(request, "complete")}>{tr("securityadministrationpanel.l85.82")}</button>}</div></div>}</article>)}</section>}
    {canPesticide && <section className="admin-subpanel"><h3>{tr("securityadministrationpanel.l86.83")}</h3><form className="record-form compact-form" onSubmit={(event) => void requestPesticide(event)}><label>{tr("securityadministrationpanel.l86.84")}<textarea name="release" rows={8} defaultValue={'{"version":"jp-2026-08","validUntil":"2027-08-01T00:00:00Z","chemicals":[]}'}/></label><div className="form-grid"><label>ticket<input name="ticketRef" required/></label><label>{tr("securityadministrationpanel.l86.85")}<input name="reason" minLength={10} required/></label></div><button className="primary-action" disabled={!online}>{tr("securityadministrationpanel.l86.86")}</button></form>{reviews.map((review) => <article key={review.id} className="audit-card"><header><strong>{String(review.proposedRelease.version || tr("securityadministrationpanel.l86.87"))}</strong><span className="status-chip">{review.status}</span></header><p>{review.reason}{tr("securityadministrationpanel.l86.88")} {review.requestedBy}</p><details><summary>{tr("securityadministrationpanel.l86.89")}</summary><div className="audit-diff"><pre>{JSON.stringify(review.beforeRelease, null, 2)}</pre><pre>{JSON.stringify(review.proposedRelease, null, 2)}</pre></div></details>{review.status === "pending" && <div className="decision-box"><label>{tr("securityadministrationpanel.l86.90")}<textarea value={decisionNotes[`pesticide-${review.id}`] || ""} onChange={(event) => setDecisionNotes((value) => ({ ...value, [`pesticide-${review.id}`]: event.target.value }))}/></label><div className="queue-actions"><button className="secondary-action" disabled={review.requestedBy === actorUserId} onClick={() => void decidePesticide(review.id, "reject")}>{tr("securityadministrationpanel.l86.91")}</button><button className="primary-action" disabled={review.requestedBy === actorUserId} onClick={() => void decidePesticide(review.id, "approve")}>{tr("securityadministrationpanel.l86.92")}</button></div>{review.requestedBy === actorUserId && <small>{tr("securityadministrationpanel.l86.93")}</small>}</div>}</article>)}</section>}
  </section>;
}
