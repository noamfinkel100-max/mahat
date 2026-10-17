## What changes

Cyphernod previously trusted client-supplied user IDs and addressed storage objects by filename. This change introduces authenticated sessions and stable file IDs, then adds owner-managed recipient download limits, UTC expiration, revocation and retryable global deletion. The upload dialog now supports drag-and-drop, replacement/removal, password-style encryption codes, actual byte progress, processing feedback and confirmed success/retry in responsive light and dark layouts.

- Authentication uses salted PBKDF2 password hashes and opaque HttpOnly/SameSite session cookies; MongoDB stores only token hashes. Sensitive API routes authorize the authenticated principal, with same-origin request checks and payload/upload limits.
- Files use unique IDs/object keys. Sharing grants are embedded in each file document so lifecycle status, grant revision, expiry and recipient counters can be enforced with one atomic MongoDB conditional update. Invalid codes do not consume allowance. Owner access is independent.
- Downloads authenticate AES-GCM into private temporary files before reserving allowance and sending plaintext. BouncyCastle streaming retains the existing CYPNOD01 format without the JDK GCM whole-ciphertext buffer. Blocking crypto/storage/filesystem operations run on Vert.x workers.
- Global deletion first marks DELETING to block new authorizations, deletes the correct S3 object, then removes grants and retains a DELETED tombstone. Failures remain inaccessible and support manual/startup/periodic cleanup retries. Success waits for required cleanup.
- React Query synchronizes owner and recipient lists, displays expiration/remaining allowance and supports policy updates/revocation and confirmed global deletion.

## Files and architecture

`BackEnd/src/main/java/org/mahat/api` replaces insecure legacy filename handlers with the authenticated `/api` contract; `security/PasswordHasher` and `handlers/Encryptor` handle account/file crypto. `Loader` reads environment configuration without logging credentials. Backend tests cover HTTP, MongoDB, real MinIO and deterministic failure/race scenarios.

`FrontEnd/src` adds cookie-session handling, relative API calls and the new vault/upload/download/sharing flows. Frontend unit/component tests, `e2e`, Playwright configuration and lint setup are included. `compose.yaml`, `compose.test.yaml`, Dockerfiles, environment examples and `.github/workflows/ci.yml` provide local and CI execution. README and `docs/` cover API, migration, consistency semantics, testing and screenshots.

## Database and migration

New `users_v2`, `sessions_v2` and `files_v2` collections isolate legacy plaintext-password and filename-keyed data. Indexes cover canonical unique account names, session TTL, owner/status and recipient/status queries. Legacy documents and objects are untouched and are not automatically trusted or imported. An owner-verified offline migration is required; duplicate legacy filenames cannot safely prove ownership. See `docs/SECURITY_AND_DATA.md` for backup, collision review, account reset/hashing, verified ciphertext-copy and cutover steps.

A recipient download is consumed after successful ciphertext authentication, when final atomic authorization succeeds just before the response. Invalid codes/storage failures consume nothing; interruption after reservation consumes it. Policy changes preserve counters. A response already authorized may finish after revocation/deletion; previously delivered bytes cannot be recalled.

## Executed validation

- Backend: 38 passed, zero failures/errors/skips — 10 encryption, 5 password, 23 real HTTP/MongoDB/MinIO integration tests.
- Frontend: 12 unit/component tests passed; TypeScript, Prettier, ESLint, stylelint and production build passed.
- Playwright: 8 Chromium scenarios passed against isolated actual MongoDB and MinIO, without mocked API responses. Includes real downloaded byte comparison, independent quotas, concurrent final allowance, deterministic expiration, custom policy editing/revocation, all-recipient deletion, actual object absence, duplicate filenames and mobile interactions.
- Full Docker app images built; all four development services healthy. Authentication, encrypted upload, exact-content download and global deletion passed a separate Compose smoke test.
- Real traffic throttling/native XHR observation verifies upload progress and server-confirmed success. Deferred-response component tests cover the 100%-transferred processing state without relying on an intermediate React frame.

Playwright HTML report is generated in `FrontEnd/playwright-report`; screenshots/traces are in `FrontEnd/test-results`. CI uploads these and Maven/service diagnostics. Review screenshots are committed in `docs/screenshots`.

## Run and manually verify

1. `cp .env.example .env` then `docker compose up --build --wait`. UI port 5173; API 12000; MongoDB 27017; MinIO 9000/9001, all published on loopback.
2. Register Alice, Bob and Charlie in separate browser contexts. Upload as Alice, share with Bob for one download and Charlie for three with an expiration. Verify invalid codes preserve allowance and downloaded bytes match.
3. Update/revoke grants. Delete Alice's file using the confirmation dialog. Refresh both recipients; direct downloads must fail and another owner's identically named file must survive.
4. Follow `docs/TESTING.md` or `.env.test.example` for isolated services and exact Maven/Playwright commands. Never point tests at retained or production data.

## CI and limitations

GitHub Actions covers frontend checks, Maven tests, isolated real services, integration tests and Playwright on pull requests to main and development pushes. No deployment secrets are required; checked-in credentials are explicitly disposable local test values. Remote Actions results have not been observed from this environment.

The production build has a non-failing JavaScript chunk-size warning (about 537 kB before gzip). MinIO is pinned to a verified historical vendor image because the upstream prebuilt distribution was unavailable. Production deployment requires its own dependency/security review, HTTPS/secure-cookie configuration, rate limiting, backups and operational limits. Existing data requires the documented offline migration. No production-readiness claim is made.

## Screenshots

![Desktop upload (dark)](https://github.com/noamfinkel100-max/mahat/blob/3c8e66ca8bcc66c8acc8f5a532703a48a003c4a5/docs/screenshots/desktop-upload-dark.png?raw=true)

![Mobile upload (light)](https://github.com/noamfinkel100-max/mahat/blob/3c8e66ca8bcc66c8acc8f5a532703a48a003c4a5/docs/screenshots/mobile-upload-light.png?raw=true)

![Mobile sharing](https://github.com/noamfinkel100-max/mahat/blob/3c8e66ca8bcc66c8acc8f5a532703a48a003c4a5/docs/screenshots/mobile-sharing.png?raw=true)

