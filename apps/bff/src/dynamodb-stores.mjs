import {
  BatchWriteItemCommand,
  DeleteItemCommand,
  DescribeTableCommand,
  GetItemCommand,
  PutItemCommand,
  QueryCommand,
  TransactWriteItemsCommand,
  UpdateItemCommand,
} from "@aws-sdk/client-dynamodb";
import { randomUUID } from "node:crypto";

const META = { S: "META" };
const VERSION = { S: "VERSION" };

const key = (sessionId, contextId = META.S) => ({ session_id: { S: sessionId }, context_id: { S: contextId } });
const number = (value) => ({ N: String(value) });
const text = (value) => ({ S: String(value) });
const epoch = (milliseconds) => Math.ceil(milliseconds / 1000);
const fromNumber = (attribute, fallback = 0) => attribute?.N == null ? fallback : Number(attribute.N);

function chunks(values, size) {
  const result = [];
  for (let index = 0; index < values.length; index += size) result.push(values.slice(index, index + size));
  return result;
}

function conditionalFailure(error) {
  return error?.name === "ConditionalCheckFailedException" || error?.name === "TransactionCanceledException";
}

export function createDynamoStores({ dynamodb, tableName, crypto, clock = () => Date.now() }) {
  if (!dynamodb?.send || !tableName || !crypto) throw new Error("DynamoDB store configuration is incomplete");

  async function encryptedItem(recordKey, recordType, value, metadata = {}) {
    const payload = await crypto.encrypt(value, { purpose: `dynamodb-${recordType}`, resourceId: recordKey });
    return {
      ...key(recordKey),
      record_type: text(recordType),
      encrypted_payload: text(payload),
      expires_at_epoch: number(epoch(value.expiresAt)),
      ...metadata,
    };
  }

  async function decryptItem(item, recordKey, recordType) {
    if (!item?.encrypted_payload?.S || fromNumber(item.expires_at_epoch) <= epoch(clock())) return null;
    const value = await crypto.decrypt(item.encrypted_payload.S, { purpose: `dynamodb-${recordType}`, resourceId: recordKey });
    if (recordType === "session" && item.last_seen_at_epoch?.N) value.lastSeenAt = fromNumber(item.last_seen_at_epoch) * 1000;
    return value;
  }

  async function get(recordKey, recordType) {
    const result = await dynamodb.send(new GetItemCommand({ TableName: tableName, Key: key(recordKey), ConsistentRead: true }));
    return decryptItem(result.Item, recordKey, recordType);
  }

  async function deleteKeys(keys) {
    for (const group of chunks(keys, 25)) {
      let pending = group.map((itemKey) => ({ DeleteRequest: { Key: itemKey } }));
      for (let attempt = 0; pending.length && attempt < 5; attempt += 1) {
        const result = await dynamodb.send(new BatchWriteItemCommand({ RequestItems: { [tableName]: pending } }));
        pending = result.UnprocessedItems?.[tableName] || [];
      }
      if (pending.length) throw new Error("DynamoDB did not complete revocation deletes");
    }
  }

  function revocationGuard(userId, authorizationVersion) {
    return {
      ConditionCheck: {
        TableName: tableName,
        Key: key(`REVOCATION#${userId}`, VERSION.S),
        ConditionExpression: "attribute_not_exists(authorization_version) OR authorization_version <= :version",
        ExpressionAttributeValues: { ":version": number(authorizationVersion) },
      },
    };
  }

  const loginAttempts = Object.freeze({
    async put(stateHash, value) {
      const recordKey = `LOGIN#${stateHash}`;
      const item = await encryptedItem(recordKey, "login", value);
      await dynamodb.send(new PutItemCommand({
        TableName: tableName,
        Item: item,
        ConditionExpression: "attribute_not_exists(session_id)",
      }));
    },
    async take(stateHash) {
      const recordKey = `LOGIN#${stateHash}`;
      const result = await dynamodb.send(new DeleteItemCommand({ TableName: tableName, Key: key(recordKey), ReturnValues: "ALL_OLD" }));
      return decryptItem(result.Attributes, recordKey, "login");
    },
  });

  const sessions = Object.freeze({
    async put(sessionHash, value) {
      const recordKey = `SESSION#${sessionHash}`;
      const version = Number(value.user.authorizationVersion);
      if (!Number.isSafeInteger(version) || version < 1) throw new Error("Session requires authorizationVersion");
      const item = await encryptedItem(recordKey, "session", value, {
        user_id: text(value.user.id),
        authorization_version: number(version),
        last_seen_at_epoch: number(epoch(value.lastSeenAt)),
      });
      try {
        await dynamodb.send(new TransactWriteItemsCommand({ TransactItems: [
          revocationGuard(value.user.id, version),
          { Put: { TableName: tableName, Item: item, ConditionExpression: "attribute_not_exists(session_id)" } },
        ] }));
      } catch (error) {
        if (conditionalFailure(error)) throw Object.assign(new Error("Session authorization is stale"), { code: "authorization_stale" });
        throw error;
      }
    },
    get: (sessionHash) => get(`SESSION#${sessionHash}`, "session"),
    async touch(sessionHash, now) {
      try {
        await dynamodb.send(new UpdateItemCommand({
          TableName: tableName,
          Key: key(`SESSION#${sessionHash}`),
          UpdateExpression: "SET last_seen_at_epoch = :now",
          ConditionExpression: "attribute_exists(encrypted_payload) AND expires_at_epoch > :now",
          ExpressionAttributeValues: { ":now": number(epoch(now)) },
        }));
        return true;
      } catch (error) {
        if (conditionalFailure(error)) return false;
        throw error;
      }
    },
    async delete(sessionHash) {
      await dynamodb.send(new DeleteItemCommand({ TableName: tableName, Key: key(`SESSION#${sessionHash}`) }));
    },
  });

  const contexts = Object.freeze({
    async put(contextHash, value) {
      const recordKey = `CONTEXT#${contextHash}`;
      const version = Number(value.authorizationVersion);
      if (!Number.isSafeInteger(version) || version < 1) throw new Error("Context requires authorizationVersion");
      const item = await encryptedItem(recordKey, "context", value, {
        user_id: text(value.userId),
        authorization_version: number(version),
        parent_session_id: text(`SESSION#${value.sessionHash}`),
      });
      try {
        await dynamodb.send(new TransactWriteItemsCommand({ TransactItems: [
          revocationGuard(value.userId, version),
          { ConditionCheck: { TableName: tableName, Key: key(`SESSION#${value.sessionHash}`), ConditionExpression: "attribute_exists(encrypted_payload)" } },
          { Put: { TableName: tableName, Item: item, ConditionExpression: "attribute_not_exists(session_id)" } },
          { Put: { TableName: tableName, Item: {
            ...key(`SESSION#${value.sessionHash}`, recordKey),
            record_type: text("context_pointer"),
            target_key: text(recordKey),
            expires_at_epoch: number(epoch(value.expiresAt)),
          } } },
        ] }));
      } catch (error) {
        if (conditionalFailure(error)) throw Object.assign(new Error("Context authorization is stale"), { code: "authorization_stale" });
        throw error;
      }
    },
    get: (contextHash) => get(`CONTEXT#${contextHash}`, "context"),
    async delete(contextHash) {
      const recordKey = `CONTEXT#${contextHash}`;
      const result = await dynamodb.send(new GetItemCommand({ TableName: tableName, Key: key(recordKey), ConsistentRead: true }));
      const parent = result.Item?.parent_session_id?.S;
      const deletes = [{ Delete: { TableName: tableName, Key: key(recordKey) } }];
      if (parent) deletes.push({ Delete: { TableName: tableName, Key: key(parent, recordKey) } });
      await dynamodb.send(new TransactWriteItemsCommand({ TransactItems: deletes }));
    },
    async deleteForSession(sessionHash) {
      const partition = `SESSION#${sessionHash}`;
      let startKey;
      const keys = [];
      do {
        const result = await dynamodb.send(new QueryCommand({
          TableName: tableName,
          KeyConditionExpression: "session_id = :session",
          ExpressionAttributeValues: { ":session": text(partition) },
          ExclusiveStartKey: startKey,
          ConsistentRead: true,
        }));
        for (const item of result.Items || []) {
          if (item.record_type?.S === "context_pointer" && item.target_key?.S) keys.push(key(item.target_key.S), key(partition, item.target_key.S));
        }
        startKey = result.LastEvaluatedKey;
      } while (startKey);
      await deleteKeys(keys);
    },
  });

  const offlineSnapshots = Object.freeze({
    async put(snapshotId, value) {
      const recordKey = `SNAPSHOT#${snapshotId}`;
      const version = Number(value.authorizationVersion);
      if (!Number.isSafeInteger(version) || version < 1) throw new Error("Offline snapshot requires authorizationVersion");
      const item = await encryptedItem(recordKey, "offline_snapshot", value, {
        user_id: text(value.userId),
        authorization_version: number(version),
      });
      await dynamodb.send(new TransactWriteItemsCommand({ TransactItems: [
        revocationGuard(value.userId, version),
        { Put: { TableName: tableName, Item: item } },
      ] }));
    },
    get: (snapshotId) => get(`SNAPSHOT#${snapshotId}`, "offline_snapshot"),
    async delete(snapshotId) {
      await dynamodb.send(new DeleteItemCommand({ TableName: tableName, Key: key(`SNAPSHOT#${snapshotId}`) }));
    },
  });

  const tokenRevocations = Object.freeze({
    async put(value) {
      if (value?.type !== "cognito_token_revoke" || typeof value.tokenSetCiphertext !== "string" || !value.tokenSetCiphertext) {
        throw new Error("Invalid deferred token revocation");
      }
      const jobId = randomUUID();
      await dynamodb.send(new PutItemCommand({
        TableName: tableName,
        Item: {
          ...key("TOKEN_REVOCATION", jobId),
          record_type: text("token_revocation"),
          encrypted_payload: text(value.tokenSetCiphertext),
          expires_at_epoch: number(epoch(clock() + 30 * 24 * 60 * 60 * 1000)),
        },
        ConditionExpression: "attribute_not_exists(session_id)",
      }));
      return jobId;
    },
    async pending(limit = 10) {
      const result = await dynamodb.send(new QueryCommand({
        TableName: tableName,
        KeyConditionExpression: "session_id = :session",
        ExpressionAttributeValues: { ":session": text("TOKEN_REVOCATION") },
        Limit: limit,
        ConsistentRead: true,
      }));
      return (result.Items || [])
        .filter((item) => fromNumber(item.expires_at_epoch) > epoch(clock()))
        .map((item) => ({ jobId: item.context_id.S, type: "cognito_token_revoke", tokenSetCiphertext: item.encrypted_payload.S }));
    },
    async delete(jobId) {
      await dynamodb.send(new DeleteItemCommand({ TableName: tableName, Key: key("TOKEN_REVOCATION", jobId) }));
    },
  });

  async function invalidate(event) {
    const version = Number(event.authorizationVersion);
    if (!event.userId || !Number.isSafeInteger(version) || version < 1) throw new Error("Invalid revocation event");
    let applied = true;
    try {
      await dynamodb.send(new PutItemCommand({
        TableName: tableName,
        Item: {
          ...key(`REVOCATION#${event.userId}`, VERSION.S),
          record_type: text("revocation"),
          user_id: text(event.userId),
          authorization_version: number(version),
          revocation_event_id: text(event.eventId),
          occurred_at: text(event.occurredAt),
        },
        ConditionExpression: "attribute_not_exists(authorization_version) OR authorization_version < :version",
        ExpressionAttributeValues: { ":version": number(version) },
      }));
    } catch (error) {
      if (!conditionalFailure(error)) throw error;
      const current = await dynamodb.send(new GetItemCommand({
        TableName: tableName,
        Key: key(`REVOCATION#${event.userId}`, VERSION.S),
        ConsistentRead: true,
      }));
      if (fromNumber(current.Item?.authorization_version) > version) return { applied: false, deleted: 0 };
      applied = false;
    }

    let startKey;
    const keys = [];
    do {
      const result = await dynamodb.send(new QueryCommand({
        TableName: tableName,
        IndexName: "user-index",
        KeyConditionExpression: "user_id = :user",
        ExpressionAttributeValues: { ":user": text(event.userId) },
        ExclusiveStartKey: startKey,
      }));
      for (const item of result.Items || []) {
        if (item.record_type?.S === "revocation" || fromNumber(item.authorization_version) >= version) continue;
        keys.push(key(item.session_id.S, item.context_id.S));
        if (item.record_type?.S === "context" && item.parent_session_id?.S) {
          keys.push(key(item.parent_session_id.S, item.session_id.S));
        }
      }
      startKey = result.LastEvaluatedKey;
    } while (startKey);
    await deleteKeys(keys);
    return { applied, deleted: keys.length };
  }

  return Object.freeze({
    loginAttempts,
    sessions,
    contexts,
    offlineSnapshots,
    tokenRevocations,
    invalidate,
    async startupCheck() {
      const result = await dynamodb.send(new DescribeTableCommand({ TableName: tableName }));
      if (result.Table?.TableStatus !== "ACTIVE") throw new Error("DynamoDB session table is not ACTIVE");
      if (!result.Table.GlobalSecondaryIndexes?.some(({ IndexName, IndexStatus }) => IndexName === "user-index" && IndexStatus === "ACTIVE")) {
        throw new Error("DynamoDB session table user-index is not ACTIVE");
      }
    },
  });
}

export const dynamoStoreKeys = Object.freeze({ key, epoch });
