import { readFile } from 'node:fs/promises';
import {
  test,
  expect,
  PASSWORD,
  CODE,
  CONTENT,
  upload,
  share,
  download,
  fileCard,
  openLibrary,
  storedFile,
  objectStatus,
} from './fixtures';

test('registration, protected pages, incorrect credentials, and secure cookie login', async ({
  actor,
}, info) => {
  const user = await actor('auth', true);
  const cookies = await user.context.cookies();
  expect(cookies.some((cookie) => cookie.httpOnly && cookie.sameSite === 'Strict')).toBe(true);
  await user.context.request.post('/api/auth/logout');
  await user.page.goto('/myfiles');
  await expect(user.page).toHaveURL(/\/login$/);
  await user.page.getByLabel(/^Username/).fill(user.name);
  await user.page.getByLabel(/^Password/).fill('definitely-wrong-password');
  await user.page.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect(user.page.getByRole('alert')).toContainText(/invalid|incorrect/i);
  await expect(user.page).toHaveURL(/\/login$/);
  await user.page.getByLabel(/^Password/).fill(PASSWORD);
  await user.page.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect(user.page).toHaveURL(/\/myfiles$/);
  await user.page.screenshot({ path: info.outputPath('desktop-library.png'), fullPage: true });
});

test('upload validates input, replaces and removes selection, tracks transfer, and confirms persistence', async ({
  actor,
}, info) => {
  const owner = await actor('upload');
  await openLibrary(owner);
  await owner.page.getByRole('button', { name: 'Switch to dark mode', exact: true }).click();
  await owner.page.getByRole('button', { name: 'Upload a file', exact: true }).click();
  const dialog = owner.page.getByRole('dialog', { name: 'Upload a file', exact: true });
  const submit = dialog.getByRole('button', { name: 'Upload file', exact: true });
  await expect(dialog).toBeVisible();
  await submit.click();
  await expect(dialog.getByText(/select a file/i).last()).toBeVisible();
  const input = dialog.getByLabel('File', { exact: true });
  await input.setInputFiles({ name: 'replace-me.txt', mimeType: 'text/plain', buffer: CONTENT });
  await expect(dialog.getByText('replace-me.txt', { exact: true })).toBeVisible();
  await submit.click();
  await expect(dialog.getByRole('alert')).toContainText(/encryption code.*8 to 256/i);
  await dialog.getByRole('button', { name: /remove.*file/i }).click();
  await expect(dialog.getByText('replace-me.txt', { exact: true })).toHaveCount(0);
  await input.setInputFiles({
    name: 'first-selection.txt',
    mimeType: 'text/plain',
    buffer: CONTENT,
  });
  // Choosing another file through the same browser file picker replaces the previous selection.
  const name = 'final-selection.txt';
  const content = Buffer.alloc(512 * 1024, 'Encrypted bytes verified in the browser.\n');
  await input.setInputFiles({ name, mimeType: 'text/plain', buffer: content });
  await expect(dialog.getByText(name, { exact: true })).toBeVisible();
  await expect(dialog.getByText('first-selection.txt', { exact: true })).toHaveCount(0);
  const encryption = dialog.getByLabel(/^Encryption code/);
  await expect(encryption).toHaveAttribute('type', 'password');
  await encryption.fill(CODE);
  await dialog.getByRole('button', { name: 'Toggle password visibility', exact: true }).click();
  await expect(encryption).toHaveAttribute('type', 'text');
  await dialog.getByRole('button', { name: 'Toggle password visibility', exact: true }).click();
  await expect(encryption).toHaveAttribute('type', 'password');
  const desktopScreenshot = info.outputPath('desktop-upload-dark.png');
  await owner.page.screenshot({ path: desktopScreenshot, fullPage: true });
  await info.attach('desktop-upload-dark', { path: desktopScreenshot, contentType: 'image/png' });
  // Throttle real browser traffic so localhost speed cannot batch every progress render away.
  // Requests still reach the actual backend and MinIO; no routes or responses are mocked.
  const network = await owner.context.newCDPSession(owner.page);
  await network.send('Network.enable');
  await network.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 100,
    downloadThroughput: 10 * 1024 * 1024,
    uploadThroughput: 128 * 1024,
  });
  // Record actual render changes without stalling or intercepting the real backend request.
  await dialog.evaluate((node) => {
    const states: string[] = [];
    const state = window as unknown as {
      uploadStates: string[];
      uploadResponseStatus: number;
      prematureUploadSuccess: boolean;
    };
    state.uploadStates = states;
    state.uploadResponseStatus = 0;
    state.prematureUploadSuccess = false;
    // Observe the native response event; requests and response content remain untouched.
    const nativeSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function send(body) {
      if (body instanceof FormData) {
        this.addEventListener(
          'load',
          () => {
            state.uploadResponseStatus = this.status;
          },
          { once: true }
        );
      }
      return nativeSend.call(this, body);
    };
    new MutationObserver(() => {
      const text = node.textContent || '';
      states.push(text);
      if (/Upload complete/.test(text) && state.uploadResponseStatus !== 201) {
        state.prematureUploadSuccess = true;
      }
    }).observe(node, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
    });
  });
  const responsePromise = owner.page.waitForResponse(
    (response) => response.url().endsWith('/api/files') && response.request().method() === 'POST'
  );
  await submit.click();
  const response = await responsePromise;
  expect(response.status()).toBe(201);
  await expect(owner.page.getByText(/upload complete|uploaded successfully/i)).toBeVisible();
  const observed = await owner.page.evaluate(() => {
    const state = window as unknown as {
      uploadStates: string[];
      uploadResponseStatus: number;
      prematureUploadSuccess: boolean;
    };
    return {
      states: state.uploadStates,
      responseStatus: state.uploadResponseStatus,
      prematureSuccess: state.prematureUploadSuccess,
    };
  });
  const { states } = observed;
  await network.detach();
  await info.attach('actual-upload-state-transitions', {
    body: JSON.stringify(observed, null, 2),
    contentType: 'application/json',
  });
  const transferred = states.findIndex((state) => /\d+% transferred/.test(state));
  const complete = states.findIndex((state) => /Upload complete/.test(state));
  expect(transferred, 'The real upload must render a transfer percentage').toBeGreaterThanOrEqual(
    0
  );
  expect(
    states.some((state) => /Keep this window open until the server confirms/.test(state))
  ).toBe(true);
  expect(complete, 'Success must follow visible byte-transfer progress').toBeGreaterThan(
    transferred
  );
  expect(observed.responseStatus, 'Success requires the real server confirmation').toBe(201);
  expect(observed.prematureSuccess, 'Success must never render before that confirmation').toBe(
    false
  );
  // Axios may flush its final progress event in the same task as a fast response, so React may
  // batch the intermediate processing render. The deferred-response unit test verifies that
  // 100% transferred with a pending response shows processing and keeps submissions disabled.
  const file = (await response.json()) as { fileId: string };
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(fileCard(owner.page, name)).toBeVisible();
  const actual = await download(owner, file.fileId);
  expect(actual.status()).toBe(200);
  expect(await actual.body()).toEqual(content);
  await owner.page.reload();
  await expect(fileCard(owner.page, name)).toBeVisible();
});

test('sharing UI, real downloaded content, invalid code, independent counters, and exhausted UI', async ({
  actor,
  mongo,
}) => {
  const owner = await actor('owner');
  const bob = await actor('bob');
  const charlie = await actor('charlie');
  const file = await upload(owner, 'shared-browser.txt');
  await openLibrary(owner);
  await owner.page
    .getByRole('button', { name: `Share ${file.originalFileName}`, exact: true })
    .click();
  const dialog = owner.page.getByRole('dialog', { name: 'Share file', exact: true });
  await dialog.getByRole('textbox', { name: 'Recipient', exact: true }).click();
  await owner.page.getByRole('option', { name: bob.name, exact: true }).click();
  await dialog.getByRole('textbox', { name: 'Download limit', exact: true }).click();
  await owner.page.getByRole('option', { name: '1 download', exact: true }).click();
  await dialog.getByRole('button', { name: 'Save sharing', exact: true }).click();
  await expect(dialog.getByText(bob.name, { exact: true })).toBeVisible();
  await share(owner, file.fileId, charlie, 3);
  await openLibrary(bob, true);
  const card = fileCard(bob.page, file.originalFileName);
  await expect(card).toBeVisible();
  await expect(card).toContainText(/1.*remaining/i);
  await bob.page
    .getByRole('button', { name: `Download ${file.originalFileName}`, exact: true })
    .click();
  const downloadDialog = bob.page.getByRole('dialog', { name: /download/i });
  await downloadDialog
    .getByLabel(/decryption code|encryption code/i)
    .fill('incorrect-decryption-code');
  await downloadDialog.getByRole('button', { name: /download/i }).click();
  await expect(downloadDialog.getByRole('alert')).toContainText(/code|decrypt/i);
  let document = await storedFile(mongo, file.fileId);
  expect(document.shares.find((grant) => grant.recipientId === bob.userId)?.downloadCount).toBe(0);
  await downloadDialog.getByLabel(/decryption code|encryption code/i).fill(CODE);
  const saved = bob.page.waitForEvent('download');
  await downloadDialog.getByRole('button', { name: /download/i }).click();
  const artifact = await saved;
  expect(await artifact.failure()).toBeNull();
  expect(await readFile((await artifact.path())!)).toEqual(CONTENT);
  await expect(card).toContainText(/limit reached|exhausted|0.*remaining/i);
  const denied = await download(bob, file.fileId);
  expect(denied.status()).toBe(403);
  expect(await denied.json()).toMatchObject({ code: 'DOWNLOAD_LIMIT_REACHED' });
  document = await storedFile(mongo, file.fileId);
  expect(document.shares.find((grant) => grant.recipientId === charlie.userId)?.downloadCount).toBe(
    0
  );
  await openLibrary(charlie, true);
  await expect(fileCard(charlie.page, file.originalFileName)).toContainText(/3.*remaining/i);
  expect((await download(charlie, file.fileId)).status()).toBe(200);
  expect((await download(owner, file.fileId)).status()).toBe(200);
});

test('concurrent final allowance is atomic and unauthorized direct requests are rejected', async ({
  actor,
  mongo,
  request,
}) => {
  const owner = await actor('atomicowner');
  const recipient = await actor('atomicrecipient');
  const outsider = await actor('outsider');
  const file = await upload(owner);
  const duplicate = await upload(owner, file.originalFileName);
  expect(duplicate.fileId).not.toBe(file.fileId);
  expect((await storedFile(mongo, duplicate.fileId)).storageObjectKey).not.toBe(
    (await storedFile(mongo, file.fileId)).storageObjectKey
  );
  await share(owner, file.fileId, recipient, 1);
  expect(
    (await request.post(`/api/files/${file.fileId}/download`, { data: { code: CODE } })).status()
  ).toBe(401);
  expect((await download(outsider, file.fileId)).status()).toBe(403);
  expect((await outsider.context.request.delete(`/api/files/${file.fileId}`)).status()).toBe(403);
  expect(
    (
      await outsider.context.request.post(`/api/files/${file.fileId}/shares`, {
        data: { recipientId: recipient.userId, maxDownloads: null, expiresAt: null },
      })
    ).status()
  ).toBe(403);
  expect(
    (
      await outsider.context.request.put(`/api/files/${file.fileId}/shares/${recipient.userId}`, {
        data: { maxDownloads: null, expiresAt: null, userId: owner.userId, _id: owner.userId },
      })
    ).status()
  ).toBe(403);
  expect(
    (
      await outsider.context.request.delete(`/api/files/${file.fileId}/shares/${recipient.userId}`)
    ).status()
  ).toBe(403);
  const crossOrigin = await owner.context.request.delete(`/api/files/${file.fileId}`, {
    headers: { Origin: 'https://untrusted.invalid' },
  });
  expect(crossOrigin.status()).toBe(403);
  expect((await storedFile(mongo, file.fileId)).status).toBe('ACTIVE');
  const results = await Promise.all([
    download(recipient, file.fileId),
    download(recipient, file.fileId),
  ]);
  expect(results.map((response) => response.status()).sort()).toEqual([200, 403]);
  expect(await results.find((response) => response.status() === 200)!.body()).toEqual(CONTENT);
  const document = await storedFile(mongo, file.fileId);
  expect(
    document.shares.find((grant) => grant.recipientId === recipient.userId)?.downloadCount
  ).toBe(1);
  expect((await download(owner, file.fileId)).status()).toBe(200);
  expect((await owner.context.request.delete(`/api/files/${file.fileId}`)).status()).toBe(204);
  expect((await download(owner, duplicate.fileId)).status()).toBe(200);
});

test('owner edits custom expiration and download policy, then confirms revocation in the UI', async ({
  actor,
  mongo,
}) => {
  const owner = await actor('policyowner');
  const recipient = await actor('policyrecipient');
  const file = await upload(owner, 'editable-policy.txt');
  await openLibrary(owner);
  await owner.page
    .getByRole('button', { name: `Share ${file.originalFileName}`, exact: true })
    .click();
  const dialog = owner.page.getByRole('dialog', { name: 'Share file', exact: true });
  await dialog.getByRole('button', { name: 'Save sharing', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText(/select a recipient/i);
  await dialog.getByRole('textbox', { name: 'Recipient', exact: true }).click();
  await owner.page.getByRole('option', { name: recipient.name, exact: true }).click();
  await dialog.getByRole('textbox', { name: 'Download limit', exact: true }).click();
  await owner.page.getByRole('option', { name: 'Unlimited', exact: true }).click();
  await dialog.getByRole('textbox', { name: 'Expires', exact: true }).click();
  await owner.page.getByRole('option', { name: 'Custom date and time', exact: true }).click();
  await dialog.getByRole('button', { name: 'Save sharing', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText(/valid|choose|date|future/i);
  const custom = new Date(Date.now() + 7 * 86_400_000).toISOString().slice(0, 16);
  await dialog.getByLabel(/^Custom expiration date and time/).fill(custom);
  await dialog.getByRole('button', { name: 'Save sharing', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('File shared successfully.');
  let document = await storedFile(mongo, file.fileId);
  expect(
    document.shares.find((grant) => grant.recipientId === recipient.userId)?.expiresAt
  ).toBeGreaterThan(Date.now());
  await dialog
    .getByRole('button', { name: `Edit access for ${recipient.name}`, exact: true })
    .click();
  await dialog.getByRole('textbox', { name: 'Download limit', exact: true }).click();
  await owner.page.getByRole('option', { name: '5 downloads', exact: true }).click();
  await dialog.getByRole('textbox', { name: 'Expires', exact: true }).click();
  await owner.page.getByRole('option', { name: 'Never expires', exact: true }).click();
  await dialog.getByRole('button', { name: 'Save sharing', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Sharing policy updated.');
  document = await storedFile(mongo, file.fileId);
  expect(
    document.shares.find((grant) => grant.recipientId === recipient.userId)?.expiresAt
  ).toBeNull();
  await openLibrary(recipient, true);
  await expect(fileCard(recipient.page, file.originalFileName)).toContainText(
    '5 downloads remaining'
  );
  await expect(fileCard(recipient.page, file.originalFileName)).toContainText('Never expires');
  await dialog
    .getByRole('button', { name: `Revoke access for ${recipient.name}`, exact: true })
    .click();
  const confirmation = owner.page.getByRole('dialog', { name: 'Revoke access?', exact: true });
  await confirmation.getByRole('button', { name: 'Revoke access', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Access revoked.');
  expect((await download(recipient, file.fileId)).status()).toBe(403);
  await recipient.page.reload();
  await recipient.page.getByRole('tab', { name: /^Shared With Me/ }).click();
  await expect(fileCard(recipient.page, file.originalFileName)).toHaveCount(0);
});

test('persistent UTC expiration, controlled expiry, updates, and limits combine correctly', async ({
  actor,
  mongo,
}) => {
  const owner = await actor('expiryowner');
  const recipient = await actor('expiryrecipient');
  const unlimited = await actor('neverexpires');
  const file = await upload(owner, 'expiration.txt');
  await share(owner, file.fileId, recipient, 3, new Date(Date.now() + 3_600_000).toISOString());
  await share(owner, file.fileId, unlimited);
  await openLibrary(recipient, true);
  await expect(fileCard(recipient.page, file.originalFileName)).toContainText(/expires|remaining/i);
  expect((await download(recipient, file.fileId)).status()).toBe(200);
  // Controlled fixture data, scoped to this test's unique file and recipient, avoids wall-clock sleeps.
  await mongo
    .db()
    .collection<{ _id: string }>('files_v2')
    .updateOne(
      { _id: file.fileId, 'shares.recipientId': recipient.userId },
      { $set: { 'shares.$.expiresAt': Date.now() - 60_000 }, $inc: { revision: 1 } }
    );
  await recipient.page.reload();
  await recipient.page.getByRole('tab', { name: /^Shared With Me/ }).click();
  await expect(fileCard(recipient.page, file.originalFileName)).toContainText(/expired/i);
  const expired = await download(recipient, file.fileId);
  expect(expired.status()).toBe(403);
  expect(await expired.json()).toMatchObject({ code: 'SHARE_EXPIRED' });
  expect((await download(unlimited, file.fileId)).status()).toBe(200);
  const updated = await owner.context.request.put(
    `/api/files/${file.fileId}/shares/${recipient.userId}`,
    {
      data: { maxDownloads: 1, expiresAt: new Date(Date.now() + 86_400_000).toISOString() },
    }
  );
  expect(updated.status(), await updated.text()).toBe(200);
  // Updating a limit preserves consumption; it cannot silently replenish an already used allowance.
  expect((await download(recipient, file.fileId)).status()).toBe(403);
  const renewed = await owner.context.request.put(
    `/api/files/${file.fileId}/shares/${recipient.userId}`,
    {
      data: { maxDownloads: 5, expiresAt: null },
    }
  );
  expect(renewed.status(), await renewed.text()).toBe(200);
  await recipient.page.reload();
  await recipient.page.getByRole('tab', { name: /^Shared With Me/ }).click();
  await expect(fileCard(recipient.page, file.originalFileName)).toContainText(/4.*remaining/i);
  expect((await download(recipient, file.fileId)).status()).toBe(200);
  const revoked = await owner.context.request.delete(
    `/api/files/${file.fileId}/shares/${recipient.userId}`
  );
  expect([200, 204], await revoked.text()).toContain(revoked.status());
  expect((await download(recipient, file.fileId)).status()).toBe(403);
  await recipient.page.reload();
  await recipient.page.getByRole('tab', { name: /^Shared With Me/ }).click();
  await expect(fileCard(recipient.page, file.originalFileName)).toHaveCount(0);
});

test('global deletion removes all recipients and real MinIO object while duplicate names remain safe', async ({
  actor,
  mongo,
}) => {
  const owner = await actor('deleteowner');
  const bob = await actor('deletebob');
  const charlie = await actor('deletecharlie');
  const file = await upload(owner, 'identical-filename.txt');
  const otherFile = await upload(
    bob,
    'identical-filename.txt',
    Buffer.from('Independent owner content')
  );
  await share(owner, file.fileId, bob, 3);
  await share(owner, file.fileId, charlie, null);
  await openLibrary(bob, true);
  await openLibrary(charlie, true);
  await expect(fileCard(bob.page, file.originalFileName)).toBeVisible();
  await expect(fileCard(charlie.page, file.originalFileName)).toBeVisible();
  const document = await storedFile(mongo, file.fileId);
  const otherDocument = await storedFile(mongo, otherFile.fileId);
  expect(await objectStatus(document.storageObjectKey)).toBe(200);
  expect(await objectStatus(otherDocument.storageObjectKey)).toBe(200);
  await openLibrary(owner);
  await owner.page
    .getByRole('button', { name: `Delete ${file.originalFileName}`, exact: true })
    .click();
  const confirmation = owner.page.getByRole('dialog', {
    name: 'Delete file everywhere?',
    exact: true,
  });
  await expect(confirmation).toContainText(/everyone|recipients|shared/i);
  await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(fileCard(owner.page, file.originalFileName)).toBeVisible();
  await owner.page
    .getByRole('button', { name: `Delete ${file.originalFileName}`, exact: true })
    .click();
  await confirmation.getByRole('button', { name: 'Delete file', exact: true }).click();
  await expect(fileCard(owner.page, file.originalFileName)).toHaveCount(0);
  await bob.page.reload();
  await charlie.page.reload();
  await bob.page.getByRole('tab', { name: /^Shared With Me/ }).click();
  await charlie.page.getByRole('tab', { name: /^Shared With Me/ }).click();
  await expect(fileCard(bob.page, file.originalFileName)).toHaveCount(0);
  await expect(fileCard(charlie.page, file.originalFileName)).toHaveCount(0);
  for (const user of [owner, bob, charlie]) {
    expect([403, 404, 410]).toContain((await download(user, file.fileId)).status());
  }
  expect(await objectStatus(document.storageObjectKey)).toBe(404);
  const tombstone = await storedFile(mongo, file.fileId);
  expect(tombstone.status).toBe('DELETED');
  expect(tombstone.shares).toEqual([]);
  expect(await objectStatus(otherDocument.storageObjectKey)).toBe(200);
  const otherDownload = await download(bob, otherFile.fileId);
  expect(otherDownload.status()).toBe(200);
  expect(await otherDownload.body()).toEqual(Buffer.from('Independent owner content'));
  expect([200, 204, 410]).toContain(
    (await owner.context.request.delete(`/api/files/${file.fileId}`)).status()
  );
  await openLibrary(bob);
  await expect(fileCard(bob.page, otherFile.originalFileName)).toBeVisible();
});

test('mobile drag and drop, accessible controls, and sharing form fit a narrow screen', async ({
  actor,
}, info) => {
  const owner = await actor('mobile');
  await owner.page.setViewportSize({ width: 390, height: 844 });
  await openLibrary(owner);
  await owner.page.getByRole('button', { name: 'Upload a file', exact: true }).click();
  const dialog = owner.page.getByRole('dialog', { name: 'Upload a file', exact: true });
  const transfer = await owner.page.evaluateHandle(() => {
    const data = new DataTransfer();
    data.items.add(
      new File(['Actual drag and drop content'], 'mobile-drag.txt', { type: 'text/plain' })
    );
    return data;
  });
  await dialog.getByTestId('upload-dropzone').dispatchEvent('dragover', { dataTransfer: transfer });
  await dialog.getByTestId('upload-dropzone').dispatchEvent('drop', { dataTransfer: transfer });
  await expect(dialog.getByText('mobile-drag.txt', { exact: true })).toBeVisible();
  await dialog.getByLabel(/^Encryption code/).fill(CODE);
  await expect(dialog.getByRole('button', { name: 'Upload file', exact: true })).toBeInViewport();
  const mobileScreenshot = info.outputPath('mobile-upload-light.png');
  await owner.page.screenshot({ path: mobileScreenshot, fullPage: true });
  await info.attach('mobile-upload-light', { path: mobileScreenshot, contentType: 'image/png' });
  await dialog.getByRole('button', { name: 'Upload file', exact: true }).click();
  await expect(dialog.getByRole('heading', { name: 'Upload complete', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(fileCard(owner.page, 'mobile-drag.txt')).toBeVisible();
  await owner.page.getByRole('button', { name: 'Share mobile-drag.txt', exact: true }).click();
  const sharing = owner.page.getByRole('dialog', { name: 'Share file', exact: true });
  await expect(sharing.getByRole('textbox', { name: 'Recipient', exact: true })).toBeVisible();
  await expect(sharing.getByRole('textbox', { name: 'Download limit', exact: true })).toBeVisible();
  await expect(sharing.getByRole('textbox', { name: 'Expires', exact: true })).toBeVisible();
  await owner.page.screenshot({ path: info.outputPath('mobile-sharing.png'), fullPage: true });
  const overflow = await owner.page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth
  );
  expect(overflow, 'Page must not overflow horizontally on mobile').toBe(false);
});
