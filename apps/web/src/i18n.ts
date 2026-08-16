import jaCatalog from "./locales/ja.json";
import enCatalog from "./locales/en.json";
import rtlCatalog from "./locales/ar-XB.json";

export type Locale = "ja" | "en" | "ar-XB";
export type TextDirection = "ltr" | "rtl";

export const localeProfiles: Record<Locale, { languageTag: string; direction: TextDirection; label: string; reviewed: boolean }> = {
  ja: { languageTag: "ja-JP", direction: "ltr", label: "日本語", reviewed: true },
  en: { languageTag: "en", direction: "ltr", label: "English (L1–L2 reviewed)", reviewed: true },
  "ar-XB": { languageTag: "ar-XB", direction: "rtl", label: "RTL test", reviewed: false },
};

const messages = {
  ja: { today: "今日", schedule: "予定", record: "記録", fields: "圃場", more: "その他", online: "オンライン", offline: "オフライン" },
  en: { today: "Today", schedule: "Schedule", record: "Record", fields: "Fields", more: "More", online: "Online", offline: "Offline" },
  "ar-XB": { today: "اليوم", schedule: "الجدول", record: "السجل", fields: "الحقول", more: "المزيد", online: "متصل", offline: "غير متصل" },
} as const;

const catalogs: Record<Locale, Record<string, string>> = { ja: jaCatalog, en: enCatalog, "ar-XB": rtlCatalog };
let activeLocale: Locale = "ja";

export type MessageKey = keyof typeof messages.ja;

export function translate(locale: Locale, key: MessageKey): string {
  return messages[locale][key] ?? messages.ja[key];
}

export function setActiveLocale(locale: Locale): void { activeLocale = locale; }

export function tr(key: string, values: ReadonlyArray<unknown> = []): string {
  const template = catalogs[activeLocale][key] ?? catalogs.ja[key];
  if (template == null) return key;
  return template.replace(/\{(\d+)\}/gu, (_match, index: string) => String(values[Number(index)] ?? ""));
}

export function formatDate(value: Date | string | number, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(localeProfiles[activeLocale].languageTag, options).format(new Date(value));
}

export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(localeProfiles[activeLocale].languageTag, options).format(value);
}

export function catalogKeys(locale: Locale): string[] { return Object.keys(catalogs[locale]); }
export function catalogMessage(locale: Locale, key: string): string | undefined { return catalogs[locale][key]; }

export function resolveLocale(value: string | null | undefined): Locale {
  if (value === "ar-XB") return value;
  return value?.toLowerCase().startsWith("en") ? "en" : "ja";
}

export function applyDocumentLocale(root: HTMLElement, locale: Locale): void {
  const profile = localeProfiles[locale];
  root.lang = profile.languageTag;
  root.dir = profile.direction;
  root.dataset.locale = locale;
}
