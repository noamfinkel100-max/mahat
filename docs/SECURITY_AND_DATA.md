# Security and data model

This change replaces the legacy unauthenticated filename API with `/api`. It is a breaking API change: old clients must update together with the backend. No production data is deleted automatically.

## Authentication and authorization

Account passwords are salted and hashed with PBKDF2. The backend issues opaque session cookies and persists only hashes of session tokens. JavaScript does not receive a reusable bearer token. Authentication comes from the server session, never a client-provided user ID. Use HTTPS and `SESSION_SECURE=true` outside local development; put the UI and API behind the same origin. Cookie-based mutations reject foreign origins. Keep MongoDB and object storage on a private network and supply their credentials through deployment configuration, never source control.

Every file has an unpredictable, stable ID and a separate object key. Original filenames are display metadata. Two users, or the same user, can upload identical filenames without overwriting an object. Only the authenticated owner manages sharing or globally deletes a file. Recipients never receive other recipients' policies or the owner's encryption code. File encryption happens on the server; this is **not end-to-end encryption**.

## Sharing and the download boundary

A file's sharing grants live in its MongoDB document. Each recipient has an independent counter, limit, UTC expiration and revocation state. Keeping the lifecycle and grants in one document allows a conditional atomic update to check that the file is active, the grant has not changed, access is unexpired and allowance remains while incrementing that recipient's counter. Frontend checks are informational.

The backend first retrieves and fully authenticates/decrypts ciphertext into a private temporary file. An incorrect encryption code, corrupt ciphertext or storage/decryption failure consumes no allowance. It then atomically reserves the download immediately before sending the HTTP response. A download is consumed when this reservation succeeds, **not** when the browser saves the last byte. Network interruption or a client disconnect after reservation still consumes it. This boundary prevents concurrent requests from spending the same final allowance and avoids unreliable refunds racing subsequent requests. Owner downloads do not use recipient allowances. Policy updates retain counters; raising a limit increases the remaining allowance without resetting usage.

A revoke, expiration, policy update or deletion that wins before the final atomic authorization prevents that download. A response already authorized can finish; no server can retract bytes already delivered. Files copied to a recipient's own disk cannot be revoked.

## Global deletion and partial failures

Deletion first atomically moves the owner's file from `ACTIVE` to `DELETING`, blocking new downloads and sharing. The backend deletes the exact object key from S3, then completes database cleanup and marks it `DELETED`. Success is returned only after required cleanup. Deleting a missing object is safe; repeating a deletion is safe. Tombstones are retained so retries cannot accidentally affect another file with the same filename.

If storage or final database cleanup fails, the operation returns an error and the file remains inaccessible. Retry the same owner's DELETE request to resume cleanup. There is no distributed MongoDB/S3 transaction and no claim of atomic rollback across services. Monitor files left in `DELETING` and retry them rather than manually restoring access. The owner UI can retry pending deletions. The backend also resumes pending deletion cleanup on startup and retries periodically every 60 seconds. Incomplete uploads stay inaccessible and stale upload records become cleanup candidates after one hour. A permanently missing object or bucket requires operator recovery from backups, not an automatic grant of access.

## Encryption compatibility

Existing `CYPNOD01` AES-256-GCM ciphertext retains its format: magic header, random 16-byte salt, random 12-byte nonce, ciphertext and 128-bit tag. Keys derive from the supplied code using PBKDF2-HMAC-SHA256. The backend does not persist raw encryption codes. Authentication/decryption must complete before plaintext is sent. Unsupported older ciphertext is rejected explicitly, never returned as plaintext or silently rewritten.

## Legacy migration: deliberate and offline

The previous schema stored plaintext passwords, filename-keyed objects, raw file codes and recipient arrays. These records are not trusted as new sessions or permissions. The new application uses versioned collections; it does not silently import or delete legacy users, objects or documents.

Before upgrading a populated deployment:

1. Stop writes to the old application and back up the database and complete object store. Keep backups restricted because legacy records may contain passwords and codes.
2. Inventory each legacy user, owner/file mapping, recipient reference and object. Filenames alone do not prove ownership. If several users reference the same object key, investigate the collision; a later overwrite may already have destroyed earlier content. Do not guess an owner or duplicate ambiguous access.
3. Require account password resets or an explicitly reviewed offline password-hashing migration. Do not copy plaintext passwords into new user documents or restore old sessions.
4. For each verified owner/object pair, assign a new file ID and new unique object key. Copy, do not move, the original ciphertext. Verify checksum, size, format and a successful decryption with an owner-supplied code before enabling it. Do not write that code into the new database or migration logs.
5. Recreate only verified recipient grants with an explicit reviewed expiration/download policy. Invalid or ambiguous grants stay disabled. Keep a restricted audit mapping of old keys to new IDs.
6. Validate owner access, recipient denial rules and duplicate filenames in staging. Cut over clients and backend together. Retain the old backups according to the owner's retention policy until recovery has been verified.

There is intentionally no automatic filename-based migration script: it cannot safely resolve collisions or invent ownership. Legacy data remains available to an operator for an audited migration; it is not visible in the new UI by default.

## Operational limits

Set a reasonable `MAX_UPLOAD_BYTES`, reverse-proxy body limit, disk quota and request timeout. Temporary storage must be writable only by the service and have enough free space for ciphertext plus decrypted files. Password hashing, encryption, filesystem and object-store operations run outside Vert.x event-loop threads. No production-readiness or measured performance claim is made by the test suite. Before public deployment, add operational rate limiting, TLS, backups, dependency monitoring and deployment-specific security review.
