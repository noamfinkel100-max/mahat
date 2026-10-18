# Cyphernod

A React/Mantine and Java/Vert.x file vault with MongoDB metadata and S3-compatible encrypted object storage.

Features include authenticated sessions, stable file IDs, recipient-specific download limits and expiration, revocation, global deletion and a redesigned upload flow. Encryption runs on the server using AES-256-GCM; the application does not claim end-to-end encryption. See [security, consistency and migration](docs/SECURITY_AND_DATA.md) before upgrading an existing deployment.

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
2. Start your existing MongoDB and MinIO/Silo containers. If they are already running on ports 27017 and 9000, reuse them. The repository Compose services can also be started with `docker compose up -d --wait mongo minio` when those ports are free.
3. Open **Run and Debug**, select **Cyphernod backend (local)**, and press **F5**. No `.env` file or manual credential export is required for an existing local MinIO container with its account configured in Docker environment settings. The launch uses an upload directory under the ignored backend `target` directory, including on Windows.

Wait for `Cyphernod API listening on port 12000`. Maven uses the same local startup configuration:

```sh
cd BackEnd
mvn compile exec:java
```

Both launchers enable the `local` profile. Java reads the nearest `.env` file automatically (the current directory, or the repository root when launched from `BackEnd`). A `.env` file is optional for local discovery and is ignored by Git. Environment variables override the optional `CONFIG_PATH` JSON file, which overrides `.env` values. Existing configured credentials are preserved.

When credentials are missing in the local profile, Java uses read-only Docker inspection to find exactly one MinIO/Silo container published on the configured loopback S3 port and reads its account settings. Container names such as `securedrop-minio` are supported. Credentials are used in memory and never printed or written to configuration files. Remote S3 endpoints are never inspected through Docker; packaged Java/Docker deployments default to the `production` profile and require configured credentials. `CYPHERNOD_PROFILE=production` explicitly disables local discovery.

Local defaults use `127.0.0.1` to avoid Docker Desktop IPv4/IPv6 mismatches. In the local profile, plain `localhost` HTTP/MongoDB URLs also use IPv4; TLS endpoints, explicit IPv6 and remote hosts remain unchanged. Startup errors identify the failing service without printing credentials. The exact Maven command from the reported PowerShell run was tested against the same Silo image; see [startup verification](docs/STARTUP_VERIFICATION.md) for the command, checks and platform limits.

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

The backend reads `.env` and environment variables; `CONFIG_PATH` remains an optional configuration-file override for existing local setups, with environment variables taking precedence. Secrets are not printed. The frontend calls relative `/api` URLs; Vite proxies to `API_PROXY_TARGET`, defaulting to the local API port 12000. Preserve the original Host when proxying so same-origin checks work. Set `ALLOWED_ORIGIN` only for an explicitly trusted frontend origin when needed.

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

## Repository guide

- `compose.yaml` starts the local app stack: frontend, backend, MongoDB and MinIO. Its named database/storage volumes persist after containers stop.
- `compose.test.yaml` starts separate, disposable MongoDB and MinIO services for automated integration and browser tests.
- `.env.example` documents local configuration; copy it to the ignored `.env` file for local use. `.env.test.example` documents test-only values. Neither example is a place for production secrets.
- `.github/workflows/ci.yml` runs frontend checks, backend tests, real-service integration tests and browser tests on GitHub Actions.
- `docs/SECURITY_AND_DATA.md` explains security boundaries, sharing/deletion behavior and legacy-data migration. `docs/TESTING.md` covers test commands. `docs/STARTUP_VERIFICATION.md` records the investigation of the local backend startup issue. `docs/screenshots/` contains UI screenshots.
- `BackEnd/` is the Java/Vert.x API; `FrontEnd/` is the React app. Each has a Dockerfile for Compose and its own development instructions.

There is no Postman collection configured. The old `.postman/` and `postman/` files were empty workspace placeholders and have been removed.

### Backend code map

- `api/Api.java` wires routes and coordinates account and file requests. Its section headings follow the request flow: startup/routes, authentication, file metadata, upload, sharing, downloads, and deletion.
- `api/SessionManager.java` owns the session cookie and database lifecycle.
- `api/ObjectStorage.java` is the small MinIO/S3 adapter.
- `handlers/Encryptor.java` owns the encrypted file format and streaming crypto; `security/PasswordHasher.java` is only for account passwords.
- `config/Configuration.java` loads settings; `Loader.java` and `Server.java` start and stop the application.
