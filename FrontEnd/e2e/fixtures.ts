import { createHash, createHmac, randomUUID } from 'node:crypto';
import { test as base, expect, BrowserContext, Page } from '@playwright/test';
import { MongoClient } from 'mongodb';

export const PASSWORD = 'Cyphernod-E2E!Password42';
export const CODE = 'private-e2e-code-42';
export const CONTENT = Buffer.from('Cyphernod real end-to-end encrypted file.\n');

export type Actor = { context: BrowserContext; page: Page; userId: string; name: string };
type StoredFile = {
  _id: string;
  fileId: string;
  ownerId: string;
  storageObjectKey: string;
  status: string;
  shares: {
    recipientId: string;
    downloadCount: number;
    expiresAt: number | null;
    revoked: boolean;
  }[];
};

export const test = base.extend<{
  actor: (label: string, registerThroughUI?: boolean) => Promise<Actor>;
  mongo: MongoClient;
}>({
  mongo: async ({}, use) => {
    // The suite may delete only its own fixtures, and will refuse any ordinary database name.
    const database = process.env.TEST_MONGO_DATABASE || 'cyphernod_e2e';
    if (!/(?:^|[_-])(?:test|e2e)(?:$|[_-])/.test(database)) {
      throw new Error('TEST_MONGO_DATABASE must contain a distinct test or e2e segment');
    }
    const databaseUri = new URL(process.env.TEST_MONGO_URI || 'mongodb://127.0.0.1:27018');
    databaseUri.pathname = `/${database}`;
    const mongo = new MongoClient(databaseUri.toString(), {
      serverSelectionTimeoutMS: 5000,
    });
    await mongo.connect();
    await use(mongo);
    await mongo.close();
  },
  actor: async ({ browser, baseURL, mongo }, use, testInfo) => {
    const actors: Actor[] = [];
    const contexts: BrowserContext[] = [];
    const names: string[] = [];
    const prefix = `e2e_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
    await use(async (label, registerThroughUI = false) => {
      const context = await browser.newContext({ baseURL });
      contexts.push(context);
      const page = await context.newPage();
      const name = `${prefix}_${label}`;
      names.push(name);
      let user: { userId: string; name: string };
      if (registerThroughUI) {
        await page.goto('/myfiles');
        await expect(page).toHaveURL(/\/login$/);
        await page.goto('/signup');
        await page.getByLabel(/^Username/).fill(name);
        await expect(page.getByLabel(/^Password/)).toHaveAttribute('type', 'password');
        await page.getByLabel(/^Password/).fill(PASSWORD);
        await page.getByRole('button', { name: 'Toggle password visibility', exact: true }).click();
        await expect(page.getByLabel(/^Password/)).toHaveAttribute('type', 'text');
        await page.getByRole('button', { name: 'Toggle password visibility', exact: true }).click();
        await expect(page.getByLabel(/^Password/)).toHaveAttribute('type', 'password');
        await page.getByRole('button', { name: 'Create account', exact: true }).click();
        await expect(page).toHaveURL(/\/myfiles$/);
        const session = await context.request.get('/api/auth/me');
        expect(session.status()).toBe(200);
        user = (await session.json()) as typeof user;
      } else {
        const registration = await context.request.post('/api/auth/register', {
          data: { name, password: PASSWORD },
        });
        expect(registration.status(), await registration.text()).toBe(201);
        user = (await registration.json()) as typeof user;
      }
      const actor = { context, page, ...user };
      actors.push(actor);
      return actor;
    });

    // Resolve exact reserved names as well, so a successful registration followed by a UI failure
    // is still cleaned even when the actor helper never returned to the test.
    const registered = await mongo
      .db()
      .collection<{ _id: string }>('users_v2')
      .find({ name: { $in: names } }, { projection: { _id: 1 } })
      .toArray();
    const userIds = registered.map((user) => user._id);
    try {
      for (const actor of actors) {
        if (testInfo.status !== testInfo.expectedStatus) {
          await testInfo.attach(`${actor.name}-screenshot`, {
            body: await actor.page.screenshot({ fullPage: true }),
            contentType: 'image/png',
          });
        }
      }
      const collection = mongo.db().collection<StoredFile>('files_v2');
      const files = await collection.find({ ownerId: { $in: userIds } }).toArray();
      for (const file of files) {
        const owner = actors.find((actor) => actor.userId === file.ownerId)!;
        const response = await owner.context.request.delete(`/api/files/${file.fileId}`);
        expect([200, 204, 410], 'Owned fixture must be cleanly deleted').toContain(
          response.status()
        );
        expect(await objectStatus(file.storageObjectKey), 'Fixture object must be absent').toBe(
          404
        );
      }
      await collection.deleteMany({ ownerId: { $in: userIds } });
      // The filter always uses the exact random users registered by this test.
      await mongo
        .db()
        .collection<{ _id: string }>('users_v2')
        .deleteMany({ _id: { $in: userIds } });
      await mongo
        .db()
        .collection('sessions_v2')
        .deleteMany({ userId: { $in: userIds } });
    } catch (error) {
      await testInfo.attach('fixture-cleanup-error', {
        body: String(error),
        contentType: 'text/plain',
      });
      throw error;
    } finally {
      await Promise.all(contexts.map((context) => context.close()));
    }
  },
});

export { expect };

export async function upload(actor: Actor, name = `${randomUUID()}.txt`, content = CONTENT) {
  const response = await actor.context.request.post('/api/files', {
    multipart: { code: CODE, file: { name, mimeType: 'text/plain', buffer: content } },
  });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as { fileId: string; originalFileName: string };
}

export async function share(
  owner: Actor,
  fileId: string,
  recipient: Actor,
  maxDownloads: 1 | 3 | 5 | null = null,
  expiresAt: string | null = null
) {
  const response = await owner.context.request.post(`/api/files/${fileId}/shares`, {
    data: { recipientId: recipient.userId, maxDownloads, expiresAt },
  });
  expect([200, 201], await response.text()).toContain(response.status());
}

export async function download(actor: Actor, fileId: string, code = CODE) {
  return actor.context.request.post(`/api/files/${fileId}/download`, { data: { code } });
}

export function fileCard(page: Page, name: string) {
  return page.getByTestId('file-card').filter({ hasText: name }).filter({ visible: true });
}

export async function openLibrary(actor: Actor, shared = false) {
  await actor.page.goto('/myfiles');
  await expect(
    actor.page.getByRole('button', { name: 'Upload a file', exact: true })
  ).toBeVisible();
  if (shared) await actor.page.getByRole('tab', { name: /^Shared With Me/ }).click();
}

export async function storedFile(mongo: MongoClient, fileId: string) {
  const file = await mongo.db().collection<StoredFile>('files_v2').findOne({ _id: fileId });
  expect(file, 'Uploaded file must have a persistent MongoDB document').not.toBeNull();
  return file!;
}

// Use the real S3 HTTP interface. SigV4 keeps this test independent of a second storage SDK.
export async function objectStatus(key: string) {
  const endpoint = new URL(process.env.TEST_S3_ENDPOINT || 'http://127.0.0.1:19000');
  const accessKey = process.env.TEST_S3_ACCESS_KEY;
  const secretKey = process.env.TEST_S3_SECRET_KEY;
  if (!accessKey || !secretKey) throw new Error('Set TEST_S3_ACCESS_KEY and TEST_S3_SECRET_KEY');
  const region = process.env.TEST_S3_REGION || 'us-east-1';
  const bucket = process.env.TEST_S3_BUCKET || 'cyphernod';
  const path = `/${[bucket, ...key.split('/')].map(encodeURIComponent).join('/')}`;
  const time = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '');
  const date = time.slice(0, 8);
  const hash = (value: string) => createHash('sha256').update(value).digest('hex');
  const payloadHash = hash('');
  const headers = `host:${endpoint.host}\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${time}\n`;
  const signedHeaders = 'host;x-amz-content-sha256;x-amz-date';
  const request = `HEAD\n${path}\n\n${headers}\n${signedHeaders}\n${payloadHash}`;
  const scope = `${date}/${region}/s3/aws4_request`;
  const signingKey = ['s3', 'aws4_request'].reduce(
    (keyValue, segment) => createHmac('sha256', keyValue).update(segment).digest(),
    createHmac('sha256', createHmac('sha256', `AWS4${secretKey}`).update(date).digest())
      .update(region)
      .digest()
  );
  const signature = createHmac('sha256', signingKey)
    .update(`AWS4-HMAC-SHA256\n${time}\n${scope}\n${hash(request)}`)
    .digest('hex');
  const response = await fetch(new URL(path, endpoint), {
    method: 'HEAD',
    headers: {
      'x-amz-date': time,
      'x-amz-content-sha256': payloadHash,
      Authorization: `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
    },
  });
  return response.status;
}
