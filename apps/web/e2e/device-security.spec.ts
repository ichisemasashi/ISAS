import { expect, test } from "@playwright/test";

test("S9: cache/outbox鍵を分離し、rotation後も読め、失効後はrecovery wrapだけで回復できる", async ({ page }) => {
  await page.goto("/?ut=1&reset=1");
  const result = await page.evaluate(async () => {
    const vault = await import("/src/device-security.ts");
    await vault.resetDeviceVaultForTesting();
    const recoveryPair = await crypto.subtle.generateKey(
      { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["wrapKey", "unwrapKey"],
    );
    await vault.configureRecoveryPublicKey("recovery-2026-08", await crypto.subtle.exportKey("jwk", recoveryPair.publicKey));
    await vault.putEncryptedCache("tenant-1", "field-group-1", "cache-1", { field: "north" });
    await vault.enqueueEncryptedOutbox("tenant-1", "event-1", { eventUuid: "event-1", memo: "unsynced" });
    const before = await vault.inspectDeviceVault();
    const cacheBefore = await vault.getEncryptedCache<{ field: string }>("tenant-1", "cache-1");
    const outboxBefore = await vault.readEncryptedOutbox<{ memo: string }>("tenant-1", "event-1");
    const cacheRotation = await vault.rotateDeviceKey("cache");
    const outboxRotation = await vault.rotateDeviceKey("outbox");
    const afterRotation = {
      cache: await vault.getEncryptedCache<{ field: string }>("tenant-1", "cache-1"),
      outbox: await vault.readEncryptedOutbox<{ memo: string }>("tenant-1", "event-1"),
    };
    let refusedUnsafeRevoke = false;
    try { await vault.revokeDeviceAccess("tenant-1", []); } catch { refusedUnsafeRevoke = true; }
    const packages = await vault.listRecoveryPackages("tenant-1");
    await vault.revokeDeviceAccess("tenant-1", packages.map((item) => item.eventId));
    const afterRevoke = await vault.inspectDeviceVault();
    const recovery = packages[0];
    const fromBase64 = (value: string) => Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
    const contentKey = await crypto.subtle.unwrapKey(
      "raw", fromBase64(recovery.wrappedContentKey), recoveryPair.privateKey, { name: "RSA-OAEP" },
      { name: "AES-GCM", length: 256 }, false, ["decrypt"],
    );
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: fromBase64(recovery.iv), additionalData: new TextEncoder().encode(recovery.aad), tagLength: 128 },
      contentKey,
      fromBase64(recovery.ciphertext),
    );
    return {
      before, cacheBefore, outboxBefore, cacheRotation, outboxRotation, afterRotation,
      refusedUnsafeRevoke, afterRevoke, recovered: JSON.parse(new TextDecoder().decode(plaintext)),
    };
  });

  expect(result.before.keys).toEqual(expect.arrayContaining([
    expect.objectContaining({ purpose: "cache", extractable: false }),
    expect.objectContaining({ purpose: "outbox", extractable: false }),
  ]));
  expect(result.cacheBefore?.field).toBe("north");
  expect(result.outboxBefore?.memo).toBe("unsynced");
  expect(result.cacheRotation).toMatchObject({ from: 1, to: 2, records: 1 });
  expect(result.outboxRotation).toMatchObject({ from: 1, to: 2, records: 1 });
  expect(result.afterRotation).toEqual({ cache: { field: "north" }, outbox: { eventUuid: "event-1", memo: "unsynced" } });
  expect(result.refusedUnsafeRevoke).toBe(true);
  expect(result.afterRevoke).toMatchObject({ keys: [], cacheRecords: 0, outboxRecords: 1 });
  expect(result.recovered).toEqual({ eventUuid: "event-1", memo: "unsynced" });
});

test("S9: storage quotaを観測し、outbox用安全余白を判定する", async ({ page }) => {
  await page.goto("/?ut=1");
  const result = await page.evaluate(async () => (await import("/src/device-security.ts")).assessStoragePressure(1024));
  expect(result.quota).toBeGreaterThan(0);
  expect(result.available).toBeGreaterThanOrEqual(0);
  expect(typeof result.safeToWriteOutbox).toBe("boolean");
});
