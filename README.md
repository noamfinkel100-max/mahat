# Cyphernod

A React/Mantine and Java/Vert.x file vault with MongoDB metadata and S3-compatible encrypted object storage.

This branch adds authenticated sessions, stable file IDs, recipient-specific download limits and expiration, revocation, global deletion and a redesigned upload flow. Encryption runs on the server using AES-256-GCM; the application does not claim end-to-end encryption. See [security, consistency and migration](docs/SECURITY_AND_DATA.md) before upgrading an existing deployment.

## Start locally

Requirements: Docker with Compose v2. Copy the example settings and start the four-service development stack:

```sh
cp .env.example .env
docker compose up --build --wait
```

The frontend uses port 5173, API 12000, MongoDB 27017, MinIO S3 9000 and its console 9001. Published service ports bind to loopback. MongoDB and MinIO use persistent named volumes. The example credentials are for local development only; use environment configuration, HTTPS and secure cookies for a deployed instance. Do not run `docker compose down -v` against data you need to retain.

The MinIO package is pinned to an immutable image digest containing the upstream April 22, 2025 release. Its historical vendor repository is used because the upstream prebuilt image is unavailable in this environment. Treat dependency updates and production storage selection as a separate deployment decision.

Create an account with a password of at least 12 characters. Upload a file with a code, share it from My Files and send the code to the recipient separately. The server does not store file codes. Recipients see expiration and remaining allowance under Shared With Me. The owner can update a policy or revoke a recipient. Deleting a file revokes all recipients and removes the unique S3 object after marking the file inaccessible.

## Develop on the host

Use Node specified by `FrontEnd/.nvmrc` and Java 17 or later with Maven 3.9.x. The npm lockfile is authoritative; Yarn is not required.

### Run the backend in VS Code

1. Open the whole `mahat` folder in VS Code, containing both `BackEnd` and `FrontEnd`. Install the **Extension Pack for Java** if VS Code prompts for Java support.
2. Copy the root `.env.example` file to `.env`. The example contains local development credentials matching the Compose MinIO service; use your own credentials if connecting to a different MinIO instance. `.env` is ignored by Git.
3. Start Docker Desktop, then open **Terminal → New Terminal** in VS Code and run `docker compose up -d --wait mongo minio` from the repository root.
4. Open **Run and Debug**, select **Cyphernod backend (local)**, and press **F5**. The checked-in launch configuration loads the root `.env` file and uses an upload directory under the ignored backend `target` directory, including on Windows.

Wait for `Cyphernod API listening on port 12000`. This VS Code launch configuration supplies the environment to Java; launching `mvn exec:java` separately does not automatically load `.env`.

```sh
cd FrontEnd
npm ci
npm run dev
```

In another terminal, set configuration using `.env.example`, then build and launch the backend:

```sh
cd BackEnd
mvn test dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" org.mahat.Loader
```

The backend reads environment variables; `CONFIG_PATH` remains an optional configuration-file override for existing local setups, with environment variables taking precedence. Secrets are not printed. The frontend calls relative `/api` URLs; Vite proxies to `API_PROXY_TARGET`, defaulting to the local API port 12000. Preserve the original Host when proxying so same-origin checks work. Set `ALLOWED_ORIGIN` only for an explicitly trusted frontend origin when needed.

## Automated checks

```sh
cd FrontEnd
npm ci
npm run typecheck
npm run lint
npm run prettier
npm run vitest -- --maxWorkers=2 --minWorkers=1
npm run build
cd ../BackEnd
mvn test
```

Unit tests run without services. Integration and Playwright tests use the separate disposable `compose.test.yaml` project; never point them at production services or an existing database.

```sh
docker compose -f compose.test.yaml up -d --wait
```

See [testing and manual verification](docs/TESTING.md) for exact service-backed commands, coverage, execution results and artifacts. GitHub Actions runs the same frontend/backend checks and the real MongoDB/MinIO browser suite on pull requests targeting main and development-branch pushes. No production credentials are needed in CI. `.env.test.example` contains the complete disposable test configuration.

## API

All sensitive routes require an HttpOnly session cookie. Error responses are JSON with `error` and `code` fields. Client user IDs are not accepted as authentication.

| Method | Route | Purpose |
| --- | --- | --- |
| POST | `/api/auth/register` | Register `{name,password}` and start a session |
| POST | `/api/auth/login` | Sign in `{name,password}` |
| POST | `/api/auth/logout` | Revoke the current session |
| GET | `/api/auth/me` | Get the signed-in account |
| GET | `/api/users` | List recipient account names and IDs |
| GET | `/api/files` | Get `{owned,shared}` file metadata |
| POST | `/api/files` | Multipart `file` and `code` upload |
| POST | `/api/files/:fileId/download` | Download with `{code}` |
| GET / POST | `/api/files/:fileId/shares` | List or create owner-managed grants |
| PUT / DELETE | `/api/files/:fileId/shares/:recipientId` | Update or revoke a grant |
| DELETE | `/api/files/:fileId` | Globally delete, or retry pending cleanup |
| GET | `/api/health` | Service readiness |

Sharing input: `{recipientId,maxDownloads,expiresAt}`. Limits are `1`, `3`, `5` or `null` for unlimited. Expiration is a future UTC ISO timestamp or `null` for never. Existing counters are retained on policy updates. Invalid codes consume no allowance; a successful final atomic download reservation consumes one even if the client disconnects afterwards. See the consistency document for concurrent revocation/deletion and interrupted-download semantics.

## Existing data

New accounts, sessions and files use `users_v2`, `sessions_v2` and `files_v2`. Grants are embedded in file documents so lifecycle state and recipient counters can be checked atomically. Legacy plaintext-password records, filename-keyed objects and stored codes are not imported automatically. They remain untouched, but do not appear in the new application. An offline owner-verified migration is required; the collision and encryption compatibility procedure is in [SECURITY_AND_DATA.md](docs/SECURITY_AND_DATA.md).
