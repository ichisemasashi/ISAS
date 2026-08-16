import { describe, expect, it } from "vitest";

import { applyDocumentLocale, localeProfiles, resolveLocale, translate } from "./i18n";

describe("locale boundary", () => {
  it("keeps every locale catalog key in parity", () => {
    const keys = ["today", "schedule", "record", "fields", "more", "online", "offline"] as const;
    for (const locale of Object.keys(localeProfiles) as Array<keyof typeof localeProfiles>) {
      for (const key of keys) expect(translate(locale, key)).not.toBe("");
    }
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
