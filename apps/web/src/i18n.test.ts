import { describe, expect, it } from "vitest";

import { applyDocumentLocale, catalogKeys, catalogMessage, localeProfiles, resolveLocale, setActiveLocale, tr, translate } from "./i18n";

describe("locale boundary", () => {
  it("keeps every locale catalog key in parity", () => {
    const keys = ["today", "schedule", "record", "fields", "more", "online", "offline"] as const;
    for (const locale of Object.keys(localeProfiles) as Array<keyof typeof localeProfiles>) {
      for (const key of keys) expect(translate(locale, key)).not.toBe("");
      expect(catalogKeys(locale)).toEqual(catalogKeys("ja"));
    }
  });

  it("renders reviewed L1-L2 English and preserves interpolation", () => {
    setActiveLocale("en");
    expect(tr("app.l370.103")).toBe("Create a work log");
    expect(tr("app.l438.131", ["2026-09-01", "2026-08-16"])).toBe("Valid until 2026-09-01 · Last synced 2026-08-16");
    expect(catalogMessage("en", "schedulepage.l57.5")).not.toBe(catalogMessage("ja", "schedulepage.l57.5"));
    setActiveLocale("ja");
  });

  it("sets language and direction for the RTL test locale", () => {
    applyDocumentLocale(document.documentElement, "ar-XB");
    expect(document.documentElement.lang).toBe("ar-XB");
    expect(document.documentElement.dir).toBe("rtl");
    expect(document.documentElement.dataset.locale).toBe("ar-XB");
  });

  it("falls back unknown browser locales to Japanese", () => {
    expect(resolveLocale("en-US")).toBe("en");
    expect(resolveLocale("vi-VN")).toBe("ja");
  });
});
