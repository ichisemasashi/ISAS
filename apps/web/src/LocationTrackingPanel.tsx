import { useEffect, useRef, useState } from "react";
import type { LocationBootstrap, MvpGateway } from "./api";
import { tr } from "./i18n";

export function LocationTrackingPanel({ api, contextId, csrfToken, locale, online, punch, setNotice }: {
  api: MvpGateway; contextId: string; csrfToken: string; locale: string; online: boolean;
  punch: "idle" | "working" | "break"; setNotice: (message: string) => void;
}) {
  const [state, setState] = useState<LocationBootstrap | null>(null);
  const sessionId = useRef(crypto.randomUUID());
  useEffect(() => {
    if (!online || !api.getLocation) return;
    const controller = new AbortController();
    api.getLocation(contextId, locale, controller.signal).then(setState).catch(() => setNotice(tr("locationtrackingpanel.l13.1")));
    return () => controller.abort();
  }, [api, contextId, locale, online, setNotice]);

  const consentGranted = state?.consent?.action === "granted";
  const collecting = Boolean(online && consentGranted && state?.preference.enabled
    && (!state.preference.punchLinked || punch === "working"));
  useEffect(() => {
    if (!collecting || !navigator.geolocation || !api.appendLocationPoints) return;
    const watchId = navigator.geolocation.watchPosition((position) => {
      void api.appendLocationPoints?.(contextId, csrfToken, { collectionSessionId: sessionId.current, points: [{
        eventUuid: crypto.randomUUID(), longitude: position.coords.longitude, latitude: position.coords.latitude,
        accuracyM: Math.max(1, position.coords.accuracy), recordedAt: new Date(position.timestamp).toISOString(),
      }] }).catch(() => setNotice(tr("locationtrackingpanel.l26.2")));
    }, () => setNotice(tr("locationtrackingpanel.l27.3")), {
      enableHighAccuracy: true, maximumAge: 30000, timeout: 15000,
    });
    return () => navigator.geolocation.clearWatch(watchId);
  }, [api, collecting, contextId, csrfToken, setNotice]);

  if (!state || !api.recordLocationConsent || !api.saveLocationPreference) return null;
  const policy = state.policies.find((item) => item.locale === locale) || state.policies[0];
  const grant = async () => {
    if (!policy) return;
    await api.recordLocationConsent?.(contextId, csrfToken, { eventUuid: crypto.randomUUID(), action: "granted",
      policyVersion: policy.policyVersion, consentTextSha256: policy.contentSha256, locale: policy.locale });
    setState(await api.getLocation?.(contextId, locale) || state); setNotice(tr("locationtrackingpanel.l39.4"));
  };
  const save = async (changes: Partial<LocationBootstrap["preference"]>) => {
    const preference = await api.saveLocationPreference?.(contextId, csrfToken, { ...state.preference, ...changes });
    if (preference) setState({ ...state, preference });
  };
  const withdraw = async () => {
    if (!state.consent) return;
    if (state.preference.enabled) await save({ enabled: false });
    await api.recordLocationConsent?.(contextId, csrfToken, { eventUuid: crypto.randomUUID(), action: "withdrawn",
      policyVersion: state.consent.policyVersion, consentTextSha256: state.consent.consentTextSha256, locale: state.consent.locale });
    setState(await api.getLocation?.(contextId, locale) || state); setNotice(tr("locationtrackingpanel.l50.5"));
  };
  return <section className="queue-panel" aria-labelledby="location-tracking-title">
    <h2 id="location-tracking-title">{tr("locationtrackingpanel.l53.6")}</h2>
    {!policy && <p>{tr("locationtrackingpanel.l54.7")}</p>}
    {policy && !consentGranted && <><h3>{policy.title}</h3><p>{policy.body}</p><button className="primary-action" disabled={!online} onClick={() => void grant()}>{tr("locationtrackingpanel.l55.8")}</button></>}
    {consentGranted && <>
      <label><input type="checkbox" checked={state.preference.enabled} disabled={!online} onChange={(event) => void save({ enabled: event.target.checked })}/>{tr("locationtrackingpanel.l57.9")}</label>
      <label><input type="checkbox" checked={state.preference.punchLinked} disabled={!online} onChange={(event) => void save({ punchLinked: event.target.checked })}/>{tr("locationtrackingpanel.l58.10")}</label>
      <label>{tr("locationtrackingpanel.l59.11")}<select value={state.preference.retentionDays} disabled={!online} onChange={(event) => void save({ retentionDays: Number(event.target.value) })}><option value="7">{tr("locationtrackingpanel.l59.12")}</option><option value="14">{tr("locationtrackingpanel.l59.13")}</option><option value="30">{tr("locationtrackingpanel.l59.14")}</option></select></label>
      <p role="status">{collecting ? tr("locationtrackingpanel.l60.15") : punch === "break" && state.preference.punchLinked ? tr("locationtrackingpanel.l60.16") : tr("locationtrackingpanel.l60.17")}</p>
      <button className="secondary-action" disabled={!online} onClick={() => void withdraw()}>{tr("locationtrackingpanel.l61.18")}</button>
    </>}
  </section>;
}
