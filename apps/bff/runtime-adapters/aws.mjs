import { CognitoIdentityProviderClient } from "@aws-sdk/client-cognito-identity-provider";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { KMSClient } from "@aws-sdk/client-kms";
import { S3Client } from "@aws-sdk/client-s3";
import { SQSClient } from "@aws-sdk/client-sqs";
import { createCognitoOidc } from "../src/cognito-oidc.mjs";
import { createDynamoStores } from "../src/dynamodb-stores.mjs";
import { createKmsEnvelopeCrypto } from "../src/envelope-crypto.mjs";
import { createPostgresIdentityAdapters } from "../src/postgres-identity.mjs";
import { createPostgresSecurityAdministration } from "../src/security-administration.mjs";
import { createRevocationService } from "../src/revocation-service.mjs";
import { createS3ObjectStorage } from "../src/s3-object-storage.mjs";

function required(env, name) {
  const value = env[name];
  if (!value || value.includes("\0")) throw new Error(`${name} is required`);
  return value;
}

export async function createRuntimeAdapters({ config, pools, logger, env = process.env, clients = {} }) {
  const region = config.region;
  const userPoolId = required(env, "COGNITO_USER_POOL_ID");
  const clientId = required(env, "COGNITO_CLIENT_ID");
  const issuer = required(env, "COGNITO_ISSUER");
  const managedLoginOrigin = required(env, "COGNITO_MANAGED_LOGIN_ORIGIN");
  const tableName = required(env, "SESSION_TABLE");
  const queueUrl = required(env, "AUTHORIZATION_REVOCATION_QUEUE_URL");
  const keyId = required(env, "TOKEN_SESSION_KMS_KEY_ARN");
  const pseudonymKey = required(env, "ACTOR_PSEUDONYM_KEY");
  const attachmentAccessPoint = required(env, "ATTACHMENT_ACCESS_POINT_ARN");

  const dynamodb = clients.dynamodb || new DynamoDBClient({ region });
  const kms = clients.kms || new KMSClient({ region });
  const sqs = clients.sqs || new SQSClient({ region });
  const cognito = clients.cognito || new CognitoIdentityProviderClient({ region });
  const s3 = clients.s3 || new S3Client({ region });
  const crypto = createKmsEnvelopeCrypto({
    kms,
    keyId,
    deploymentId: config.deploymentId,
    jurisdiction: config.jurisdiction,
  });
  const stores = createDynamoStores({ dynamodb, tableName, crypto });
  const postgres = createPostgresIdentityAdapters({
    pool: pools.authP1,
    jurisdiction: config.jurisdiction.toLowerCase(),
    shardId: config.deploymentId,
    pseudonymKey,
  });
  const securityAdministration = createPostgresSecurityAdministration({ pool: pools.authP1 });
  const attachmentStorage = createS3ObjectStorage({
    s3,
    bucket: attachmentAccessPoint,
    downloadTtlSeconds: Number(env.ATTACHMENT_DOWNLOAD_TTL_SECONDS || 60),
  });
  let revocations;
  const identityProvider = createCognitoOidc({
    issuer,
    userPoolId,
    clientId,
    managedLoginOrigin,
    cognito,
    crypto,
    enqueueRevocation: (event) => revocations.enqueueTokenRevocation(event),
  });
  revocations = createRevocationService({
    sqs,
    queueUrl,
    outbox: postgres.revocationOutbox,
    stores,
    identityProvider,
    logger,
  });

  let lastReadinessAt = 0;
  async function dependenciesCheck() {
    await Promise.all([
      stores.startupCheck(),
      revocations.startupCheck(),
      identityProvider.startupCheck({ redirectUri: config.redirectUri, logoutUri: `${config.origin}/` }),
      attachmentStorage.startupCheck(),
    ]);
    lastReadinessAt = Date.now();
  }

  return Object.freeze({
    stores: Object.freeze({
      loginAttempts: stores.loginAttempts,
      sessions: stores.sessions,
      contexts: stores.contexts,
    }),
    offlineSnapshots: stores.offlineSnapshots,
    identityProvider,
    users: postgres.users,
    authorization: postgres.authorization,
    securityAdministration,
    attachmentStorage,
    revocations,
    async startupCheck() {
      await dependenciesCheck();
      revocations.start();
    },
    async readinessCheck() {
      if (Date.now() - lastReadinessAt >= 30000) await dependenciesCheck();
    },
    async close() {
      await revocations.close();
      for (const client of [dynamodb, kms, sqs, cognito, s3]) client.destroy?.();
    },
  });
}
