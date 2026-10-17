# Verification

The automated suite uses real MongoDB and MinIO. API requests in browser tests use the same session cookies as the browser. No API responses are mocked. Test fixtures use random account names and unique object IDs, and inspect the isolated database and object store directly for assertions and cleanup.

## Isolated services

```sh
docker compose -f compose.test.yaml up -d --wait
```

This separate project publishes MongoDB on 27018 and MinIO on 19000. Storage is temporary. `docker compose -f compose.test.yaml down` destroys this isolated test service data; do not use this file with a production service or retained user data.

## Unit and integration tests

```sh
cd BackEnd
mvn test
```

The service-backed integration suite requires the isolated services. It creates a randomly named `cyphernod_test_*` database and bucket and removes only those resources after the run:

```sh
cd BackEnd
RUN_INTEGRATION_TESTS=true \
TEST_MONGO_URL=mongodb://127.0.0.1:27018 \
TEST_S3_ENDPOINT=http://127.0.0.1:19000 \
TEST_S3_ACCESS_KEY=cyphernod-test \
TEST_S3_SECRET_KEY=cyphernod-test-only-password \
mvn -Dtest=SecurityIntegrationTest test
```

The integration suite verifies authorization, password/session handling, unique IDs, independent allowances, atomic concurrency, expiry/revocation during decryption, deletion consistency, storage failure recovery and restart recovery. Failure injection wraps real MinIO operations; successful operations still use actual storage.

## Browser tests

Build the backend and its runtime classpath, then launch it with isolated data:

```sh
cd BackEnd
mvn test dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
PORT=12001 \
MONGO_URL=mongodb://127.0.0.1:27018 \
MONGO_DB_NAME=cyphernod_e2e \
S3_ENDPOINT=http://127.0.0.1:19000 \
S3_ACCESS_KEY=cyphernod-test \
S3_SECRET_KEY=cyphernod-test-only-password \
S3_BUCKET=cyphernod-e2e \
SESSION_SECURE=false \
UPLOAD_DIR=/tmp/cyphernod-e2e-uploads \
java -cp "target/classes:$(cat target/classpath.txt)" org.mahat.Loader
```

In another terminal:

```sh
cd FrontEnd
npm ci
npx playwright install --with-deps chromium
API_PROXY_TARGET=http://127.0.0.1:12001 \
TEST_MONGO_URI=mongodb://127.0.0.1:27018 \
TEST_MONGO_DATABASE=cyphernod_e2e \
TEST_S3_ENDPOINT=http://127.0.0.1:19000 \
TEST_S3_ACCESS_KEY=cyphernod-test \
TEST_S3_SECRET_KEY=cyphernod-test-only-password \
TEST_S3_BUCKET=cyphernod-e2e \
npm run test:e2e
```

You can also export the values in the root `.env.test.example` instead of repeating these local test variables.

The Playwright configuration starts Vite or reuses an existing development server locally. Override `PLAYWRIGHT_BASE_URL` for a different frontend port. A preinstalled Chromium can be selected using `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`; this cloud instance uses `/usr/bin/chromium`. The isolated Mongo database name must include a `test` or `e2e` segment; the fixture refuses an ordinary database name. Direct S3 inspections require the explicit test credentials above.

Reports are generated under `FrontEnd/playwright-report`, screenshots and failure traces under `FrontEnd/test-results`. Run `npm run test:e2e:report` to inspect them locally. CI uploads reports, screenshots and traces as build artifacts. Generated reports, local configuration and real credentials are ignored by Git.

## Manual verification

1. Create Alice, Bob and Charlie accounts with distinct credentials. Open each in a separate browser context to keep sessions separate.
2. As Alice, open Upload a file. Try submitting with no file and with no code. Browse or drag a file, replace/remove it, then upload with a code. Confirm the UI shows transfer/processing before server-confirmed success.
3. Share with Bob for one download and Charlie for three, choosing an expiration. Bob should see the remaining count and expiration. An incorrect code should fail without consuming allowance. A correct download should match the original bytes. A second Bob download must fail while Charlie retains his own allowance.
4. Change a recipient limit or expiration from the owner sharing dialog, then revoke it. Refresh the recipient to verify persisted state. Direct unauthorized API calls must fail too.
5. Upload another file with the same filename under another owner. Delete Alice's original file using the confirmation dialog. Both recipients must lose it after refresh or polling; the other owner's file remains downloadable.
6. Repeat on a narrow mobile viewport and in both color themes. Check selection, code reveal, share settings, error messages and deletion controls remain reachable.

Download limits apply when the verified-decryption request wins the final atomic reservation. A client disconnect after that point consumes the allowance; see [security and data semantics](SECURITY_AND_DATA.md). Staging and production require separate TLS, operational, backup and deployment checks.

## Cloud build proxy support

Normal Docker builds use their standard certificate trust and need no extra flags. This cloud instance routes dependency downloads through a TLS proxy. Its image-build validation used optional BuildKit secret mounts for Maven proxy settings, the existing Java truststore and npm certificate trust, plus proxy build arguments. Secrets are mounted only for the build commands and do not become image layers. Package checksums and TLS verification stayed enabled. Do not disable certificate checks to work around a local proxy; supply the trusted certificate/settings using the documented optional mounts in the Dockerfiles.

## Results from this branch

Executed successfully in the cloud environment against actual MongoDB 7.0 and MinIO 2025-04-22:

- Backend: 68 tests passed, 0 failures/errors/skips (22 configuration/discovery, 4 startup diagnostics, 10 encryption, 5 password, 27 HTTP/database/storage/startup integration).
- Frontend: 12 unit/component tests passed; TypeScript, Prettier, ESLint, stylelint and the production build passed.
- Playwright: 8 Chromium scenarios passed against the isolated real backend. Registration/login, protected pages, upload validation/replacement/drag-drop, actual progress, correct downloaded bytes, limits, concurrent final allowance, expiration, policy edits/revocation, global deletion, duplicate filenames and mobile controls were exercised.
- Both application Docker images built and all four development services became healthy. A separate Compose smoke test passed registration, session lookup, encrypted upload, exact-content download and deletion.

The browser test throttles real traffic and observes native XHR events to verify numeric progress and server-confirmed success. A deferred-response component test verifies the 100%-transferred processing state, which fast responses may batch away without rendering an intermediate frame. No product delay was added to satisfy a timing assumption.

The build reports a JavaScript chunk-size warning (about 537 kB before gzip). This is a warning, not a failed build. GitHub Actions is configured but its remote result has not been observed from this environment. These checks do not constitute a production security audit.

Screenshots captured from the validated application using isolated review fixtures, after modal transitions completed:

- [Desktop upload, dark theme](screenshots/desktop-upload-dark.png)
- [Desktop library](screenshots/desktop-library.png)
- [Mobile upload, light theme](screenshots/mobile-upload-light.png)
- [Mobile sharing](screenshots/mobile-sharing.png)

## Local startup regression

Java now loads `.env` from the repository root or `BackEnd` itself, so Maven and VS Code use the same configuration. The Maven exec plugin and VS Code local launch select the local profile. Missing credentials are resolved from the existing MinIO/Silo container bound to the configured loopback port, without writing or logging them. Explicit settings retain precedence, and production/remote endpoints do not use Docker credential discovery.

The exact `mvn compile exec:java` launch was executed with neither a `.env` file nor exported S3 credentials against isolated actual MongoDB and MinIO with non-default account settings. API health and account registration passed, and storage initialization authenticated successfully. The additional 19 tests cover settings precedence, Windows/literal dotenv values, redacted error messages, production isolation, matching ports/images, the existing Silo image name, unavailable Docker and ambiguous-container rejection.

The follow-up startup suite also verified the exact fully qualified Maven exec command against the user's Silo image, including an IPv6-only localhost regression and real startup failure cases. See [STARTUP_VERIFICATION.md](STARTUP_VERIFICATION.md).
