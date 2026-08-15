import assert from "node:assert/strict";
import { test } from "node:test";
import { createDynamoStores } from "../src/dynamodb-stores.mjs";

function fakeDynamo() {
  const items = new Map();
  const itemKey = (value) => `${value.session_id.S}|${value.context_id.S}`;
  const canceled = () => Object.assign(new Error("condition"), { name: "TransactionCanceledException" });
  const conditional = () => Object.assign(new Error("condition"), { name: "ConditionalCheckFailedException" });
  const client = { async send(command) {
    const input = command.input;
    switch (command.constructor.name) {
      case "DescribeTableCommand": return { Table: { TableStatus: "ACTIVE", GlobalSecondaryIndexes: [{ IndexName: "user-index", IndexStatus: "ACTIVE" }] } };
      case "GetItemCommand": return { Item: items.get(itemKey(input.Key)) };
      case "PutItemCommand": {
        const id = itemKey(input.Item);
        const previous = items.get(id);
        if (input.ConditionExpression?.includes("authorization_version <")
          && previous && Number(previous.authorization_version.N) >= Number(input.ExpressionAttributeValues[":version"].N)) throw conditional();
        if (input.ConditionExpression === "attribute_not_exists(session_id)" && previous) throw conditional();
        items.set(id, structuredClone(input.Item));
        return {};
      }
      case "DeleteItemCommand": {
        const id = itemKey(input.Key);
        const previous = items.get(id);
        items.delete(id);
        return { Attributes: input.ReturnValues ? previous : undefined };
      }
      case "UpdateItemCommand": {
        const id = itemKey(input.Key);
        const previous = items.get(id);
        if (!previous) throw conditional();
        previous.last_seen_at_epoch = input.ExpressionAttributeValues[":now"];
        return {};
      }
      case "TransactWriteItemsCommand": {
        for (const operation of input.TransactItems) {
          if (!operation.ConditionCheck) continue;
          const existing = items.get(itemKey(operation.ConditionCheck.Key));
          const version = Number(operation.ConditionCheck.ExpressionAttributeValues?.[":version"]?.N);
          if (operation.ConditionCheck.Key.session_id.S.startsWith("REVOCATION#")
            && existing && Number(existing.authorization_version.N) > version) throw canceled();
          if (operation.ConditionCheck.ConditionExpression === "attribute_exists(encrypted_payload)" && !existing?.encrypted_payload) throw canceled();
        }
        for (const operation of input.TransactItems) {
          if (operation.Put) items.set(itemKey(operation.Put.Item), structuredClone(operation.Put.Item));
          if (operation.Delete) items.delete(itemKey(operation.Delete.Key));
        }
        return {};
      }
      case "QueryCommand": {
        const values = [...items.values()];
        if (input.IndexName === "user-index") return { Items: values.filter((item) => item.user_id?.S === input.ExpressionAttributeValues[":user"].S) };
        return { Items: values.filter((item) => item.session_id.S === input.ExpressionAttributeValues[":session"].S) };
      }
      case "BatchWriteItemCommand": {
        for (const request of input.RequestItems[Object.keys(input.RequestItems)[0]]) items.delete(itemKey(request.DeleteRequest.Key));
        return {};
      }
      default: throw new Error(`unexpected ${command.constructor.name}`);
    }
  } };
  return { client, items };
}

test("Dynamo stores encrypt records, maintain context pointers, and reject stale authorization", async () => {
  const now = Date.parse("2026-08-15T00:00:00Z");
  const db = fakeDynamo();
  const crypto = {
    async encrypt(value) { return Buffer.from(JSON.stringify(value)).toString("base64url"); },
    async decrypt(value) { return JSON.parse(Buffer.from(value, "base64url").toString()); },
  };
  const stores = createDynamoStores({ dynamodb: db.client, tableName: "session-table", crypto, clock: () => now });
  await stores.startupCheck();

  await stores.loginAttempts.put("state", { verifier: "secret", expiresAt: now + 60000 });
  assert.deepEqual(await stores.loginAttempts.take("state"), { verifier: "secret", expiresAt: now + 60000 });
  assert.equal(await stores.loginAttempts.take("state"), null);

  await stores.sessions.put("session", {
    user: { id: "user-1", authorizationVersion: "7" }, lastSeenAt: now, expiresAt: now + 60000,
  });
  await stores.contexts.put("context", {
    sessionHash: "session", userId: "user-1", authorizationVersion: "7", expiresAt: now + 30000,
  });
  await stores.offlineSnapshots.put("snapshot", {
    userId: "user-1", authorizationVersion: "7", expiresAt: now + 30000,
  });
  assert.equal((await stores.sessions.get("session")).user.id, "user-1");
  assert.equal((await stores.contexts.get("context")).sessionHash, "session");

  const result = await stores.invalidate({
    eventId: "8", userId: "user-1", authorizationVersion: 8, occurredAt: "2026-08-15T00:00:01Z",
  });
  assert.equal(result.applied, true);
  assert.equal(await stores.sessions.get("session"), null);
  assert.equal(await stores.contexts.get("context"), null);
  assert.equal(await stores.offlineSnapshots.get("snapshot"), null);
  await assert.rejects(() => stores.sessions.put("stale", {
    user: { id: "user-1", authorizationVersion: "7" }, lastSeenAt: now, expiresAt: now + 60000,
  }), /stale/);
});

test("deleteForSession removes every pointed context without a table scan", async () => {
  const now = Date.parse("2026-08-15T00:00:00Z");
  const db = fakeDynamo();
  const crypto = {
    async encrypt(value) { return JSON.stringify(value); },
    async decrypt(value) { return JSON.parse(value); },
  };
  const stores = createDynamoStores({ dynamodb: db.client, tableName: "session-table", crypto, clock: () => now });
  await stores.sessions.put("s", { user: { id: "u", authorizationVersion: "1" }, lastSeenAt: now, expiresAt: now + 60000 });
  await stores.contexts.put("c1", { sessionHash: "s", userId: "u", authorizationVersion: "1", expiresAt: now + 60000 });
  await stores.contexts.put("c2", { sessionHash: "s", userId: "u", authorizationVersion: "1", expiresAt: now + 60000 });
  await stores.contexts.deleteForSession("s");
  assert.equal(await stores.contexts.get("c1"), null);
  assert.equal(await stores.contexts.get("c2"), null);
});
