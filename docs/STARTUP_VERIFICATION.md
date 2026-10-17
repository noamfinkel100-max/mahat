# Verification of the reported Maven startup command

The server was started from `BackEnd` using the same application command shown in the PowerShell screenshot:

```sh
mvn org.codehaus.mojo:exec-maven-plugin:3.6.3:java "-Dexec.mainClass=org.mahat.Loader"
```

The current sources were compiled by the backend test run before launching that command. The command itself does **not** compile Java sources; after changing Java files, `mvn compile` or the IDE's Java build must update `target/classes` before this exec goal can use them.

## Services and configuration

- Real Silo image: `pgsty/silo:RELEASE.2026-09-16T00-00-00Z`, matching the user's Docker screenshot. Pulled digest: `sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46`.
- Real MongoDB image: `mongo:7`.
- Published loopback ports: S3 9000, console 9001, MongoDB 27017, backend 12000.
- No `.env` file and no exported S3 access/secret keys. The local launcher discovered the existing container's configured test account through Docker.
- Maven's proxy/cache settings were supplied for this cloud environment; they do not change the application configuration.

## Observed results

The exact Maven command reached `Cyphernod API listening on port 12000`. Actual HTTP checks then passed:

1. Database-backed API health and registration of isolated owner/recipient accounts.
2. Encrypted file upload into the actual Silo object store.
3. Sharing with one allowed download and a UTC expiration.
4. Incorrect-code rejection without consuming the allowance.
5. A recipient download matching the original bytes exactly.
6. Rejection of the recipient's second download while owner download remained allowed.
7. Global deletion, recipient download rejection and removal from the recipient's file list.

All created accounts, file records and objects were cleaned up. Existing user data was not used.

## IPv6 regression and error diagnostics

A second launch used a JVM hosts file mapping `localhost` only to `::1`, while Docker exposed IPv4 loopback ports. Before the fix this reproduced the old generic `Backend startup failed: check service configuration and connectivity.` message.

The local launcher now uses IPv4 loopback for its defaults and normalizes plain local `localhost` HTTP/MongoDB URLs, including already configured local ports. Remote hosts, explicit IPv6 and TLS endpoints are preserved; credential and query text is not decoded or rewritten. The exact command and full functional checks above passed with the IPv6-only hosts file both with defaults and with explicitly configured `localhost` URLs.

Startup failures now distinguish storage authentication, S3 connection/TLS failures, database initialization, an occupied HTTP port and an unwritable upload directory. Messages omit credentials, connection strings, raw response bodies and underlying exception text. Tests exercise real S3 authentication rejection, unavailable MongoDB, port conflicts and filesystem failures at the `Server` entry point.

The complete backend suite passed **68 tests with zero failures, errors or skips**, including the real MongoDB/Silo tests. CI includes the new startup integration suite and starts the backend for Playwright through the same Maven exec goal.

This verification ran on Linux with Java 21 compiling Java 17 sources. It includes an IPv6-only localhost simulation; it is not a claim that the user's Windows machine was accessed or tested directly. A matching generic message was reproduced, but the original screenshot alone does not establish that IPv6 was the only cause on that machine.
