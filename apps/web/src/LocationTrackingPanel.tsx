import { useEffect, useRef, useState } from "react";
import type { LocationBootstrap, MvpGateway } from "./api";

export function LocationTrackingPanel({ api, contextId, csrfToken, locale, online, punch, setNotice }: {
  api: MvpGateway; contextId: string; csrfToken: string; locale: string; online: boolean;
  punch: "idle" | "working" | "break"; setNotice: (message: string) => void;
}) {
  const [state, setState] = useState<LocationBootstrap | null>(null);
  const sessionId = useRef(crypto.randomUUID());
  useEffect(() => {
    if (!online || !api.getLocation) return;
    const controller = new AbortController();
    api.getLocation(contextId, locale, controller.signal).then(setState).catch(() => setNotice("位置情報の設定を取得できませんでした。"));
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
      }] }).catch(() => setNotice("位置情報を保存できませんでした。設定と打刻状態を確認してください。"));
    }, () => setNotice("端末の位置情報を取得できません。OS・ブラウザーの許可を確認してください。"), {
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
    setState(await api.getLocation?.(contextId, locale) || state); setNotice("位置情報の利用に同意しました。");
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
    setState(await api.getLocation?.(contextId, locale) || state); setNotice("位置情報の同意を撤回しました。");
  };
  return <section className="queue-panel" aria-labelledby="location-tracking-title">
    <h2 id="location-tracking-title">位置ログ・在圃時間</h2>
    {!policy && <p>この言語で有効な同意文面が公開されていません。位置情報は収集しません。</p>}
    {policy && !consentGranted && <><h3>{policy.title}</h3><p>{policy.body}</p><button className="primary-action" disabled={!online} onClick={() => void grant()}>内容に同意する</button></>}
    {consentGranted && <>
      <label><input type="checkbox" checked={state.preference.enabled} disabled={!online} onChange={(event) => void save({ enabled: event.target.checked })}/>位置ログを有効にする</label>
      <label><input type="checkbox" checked={state.preference.punchLinked} disabled={!online} onChange={(event) => void save({ punchLinked: event.target.checked })}/>打刻と連動する</label>
      <label>保持日数<select value={state.preference.retentionDays} disabled={!online} onChange={(event) => void save({ retentionDays: Number(event.target.value) })}><option value="7">7日</option><option value="14">14日</option><option value="30">30日</option></select></label>
      <p role="status">{collecting ? "位置情報を記録中です。" : punch === "break" && state.preference.punchLinked ? "休憩中のため位置記録を停止しています。" : "位置情報は記録していません。"}</p>
      <button className="secondary-action" disabled={!online} onClick={() => void withdraw()}>同意を撤回する</button>
    </>}
  </section>;
}
