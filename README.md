# Audit Log Service

A tamper-evident, append-only audit log service. Each record is chained to the one before it
via a SHA-256 content hash, so any modification to a past record — or any deletion, reordering,
or substitution — is detectable by walking the chain.

Built for the Charles Schwab / Persistent take-home assignment ("Interview Assignment: Build an
AI-Assisted Software Engineering System — Audit Log Service", v2.0). Covers all three scenarios:
core service (A), retention/redaction/export (B), and the ambiguous compliance-reporting
requirement (C, clarified in `SCENARIO_C.md`). See `AI_USAGE_LOG.md` for AI-assistance
traceability, `ATTESTATION.md` for the required attestation, and `SUMMARY.md` for the final
engineering summary (plan, artifacts, risks, trade-offs, assumptions, limitations).

## Requirements

- Java 21
- Maven 3.9+ (or use the included `./mvnw` wrapper)

## Running

```
./mvnw spring-boot:run
```

The service listens on `http://localhost:8080`. It uses a file-based H2 database at `./data/auditlog`
(gitignored) so state survives restarts and can be inspected/tampered with directly — see
"Manually inspecting or tampering with the data store" below. The H2 console is available at
`http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/auditlog`, user `sa`, no password).

## Running tests

```
./mvnw test
```

Unit tests cover the canonical-JSON serialization, content-hashing, and payload-manifest
primitives. Integration tests exercise the full validation flow the assignment describes for each
scenario: `AuditLogApiIntegrationTest` (write/query/verify/tamper), `RedactionIntegrationTest`,
`RetentionIntegrationTest`, `ExportIntegrationTest`, `ComplianceReportIntegrationTest` — 28 tests
total as of the last commit.

## API

### `POST /audit/events` — append a record

```json
{
  "eventType": "USER_LOGIN",
  "actorId": "alice",
  "resourceType": "SESSION",
  "resourceId": "sess-1",
  "payload": { "ip": "10.0.0.1", "mfa": true },
  "occurredAt": "2026-08-20T12:00:00Z"
}
```

`occurredAt` is optional (see "Timestamps" below). Returns `201 Created` with the stored record,
including its `sequenceNumber`, `contentHash`, and `previousHash`.

There is intentionally no `PUT`/`PATCH`/`DELETE` on this resource — the API has no way to modify
or remove a record once written.

### `GET /audit/events` — query

Query params (all optional, combinable): `actorId`, `resourceType`, `resourceId`, `eventType`,
`from`, `to` (ISO-8601, filtered against `occurredAt`), `includeArchived` (default `false`),
`page`, `size`, `sort`. Returns a paginated envelope (`content`, `page`, `size`, `totalElements`,
`totalPages`).

### `GET /audit/events/{id}` — fetch one record by its id.

### `GET /audit/verify` — walk the chain

```json
{
  "intact": false,
  "recordsChecked": 1,
  "firstBrokenSequenceNumber": 1,
  "violationType": "CONTENT_HASH_MISMATCH",
  "details": "stored content_hash does not match a hash recomputed from the record's current field values - the record was modified after it was written"
}
```

### `POST /audit/events/{id}/redact` — Scenario B: redact payload fields

```json
{ "fields": ["accountNumber"], "actorId": "compliance-officer-1", "reason": "PCI scope reduction" }
```

Replaces the listed payload fields' live values with `"[REDACTED]"` without invalidating the
record's `contentHash` (see "Redaction" below). Idempotent; rejects field names that don't exist
in the payload. Appends a companion `PAYLOAD_REDACTED` event to the chain.

### `POST /audit/retention/archive?olderThanDays=` — Scenario B: retention

Archives records older than the configured window (`audit.retention.archive-after-days`, default
90; override per-call with `olderThanDays` for testing). Archiving only flips `archived`/
`archivedAt` — never a hash-relevant field — so archived records still verify cleanly. Archived
records are hidden from `GET /audit/events` unless `includeArchived=true`.

### `GET /audit/export?resourceId=|actorId=&includeArchived=` — Scenario B: bulk export

Returns an `ExportBundle`: every matching record (sequence order) with everything needed to
re-derive its hashes, plus a `bundleHash` covering the whole bundle. See "Bulk export" below for
exactly what independent verification of this bundle does and doesn't prove.

### `POST /audit/export/verify` — re-verify a bundle

Body: an `ExportBundle` (e.g. one just received from `/audit/export`). Reruns the same checks a
recipient would run independently, using only the bundle JSON — no database access.

### `GET /audit/compliance-report?resourceId=&resourceType=ACCOUNT&from=&to=&format=json|csv` — Scenario C

See `SCENARIO_C.md` for the clarified requirement. Returns chronological access rows for one
resource plus a `chainIntact` flag; `format=csv` returns a downloadable file instead of JSON.

## Architecture

### Hash chain

Each record stores:
- `contentHash` — SHA-256 of the record's own fields (sequenceNumber, eventType, actorId,
  resourceType, resourceId, **payload field-hash manifest** — see "Redaction" below, occurredAt,
  recordedAt), joined with the ASCII SOH control character (0x01) as a field separator. This rules
  out field-boundary ambiguity (e.g. `actorId="a"` + `resourceType="bc"` hashing identically to
  `actorId="ab"` + `resourceType="c"`) without needing a full canonical envelope for the whole
  record.
- `previousHash` — the prior record's `contentHash`, or 64 zero characters (`GENESIS_HASH`) for
  the first record.

(Scenario A originally hashed the raw canonical payload text directly; it was reworked to hash a
per-field manifest instead once Scenario B's redaction requirement made that necessary — see
"Redaction".)

Verification (`ChainVerificationService`) walks records in `sequenceNumber` order. For each one it
recomputes `contentHash` from the record's current stored fields and compares it to the stored
value (catches direct tampering with that record), then checks the stored `previousHash` against
the *previous* record's `contentHash` (catches deletion, reordering, or substitution of records).
It reports the first sequence number where either check fails, and which kind of violation it was.

Chain order is the DB-assigned `sequenceNumber`, not the caller-supplied `occurredAt` — a client
cannot influence its own position in the chain by choosing a timestamp.

### Canonical JSON

The `payload` field is arbitrary caller-supplied JSON. Because it gets hashed, and because it will
be re-serialized from the database at verification time, hashing needs to be deterministic
regardless of input key order or whitespace. `CanonicalJson` parses the payload and re-serializes
it with object keys sorted recursively (array order is preserved, since array order is usually
semantically meaningful). The *canonical* form is what's stored and what's hashed, so re-hashing
at verify time reproduces the same bytes that were hashed at write time.

Note: Spring Boot 4 ships **Jackson 3** (`tools.jackson.*`), not the Jackson 2
(`com.fasterxml.jackson.databind.*`) that most existing documentation and examples reference. The
sorted-map-keys feature in particular moved from `MapperFeature` to `SerializationFeature`.

### Timestamps

Two timestamp fields exist because "when it happened" and "when we recorded it" are genuinely
different things for an audit log, and conflating them creates a gap an attacker (or a buggy
client) can exploit:

- `occurredAt` — caller-supplied claim of when the event happened. Optional; defaults to
  `recordedAt` if omitted. **Not trusted** for chain ordering or integrity — a malicious or buggy
  client could supply any value here.
- `recordedAt` — server-assigned wall-clock time when the record was appended. Authoritative for
  ordering (alongside `sequenceNumber`) and for retention/archival decisions (Scenario B).

### Redaction

The original hash covers the original value, so simply clearing a sensitive field would
invalidate the record's hash — Scenario B's stated "genuine engineering problem." The fix: hash
each top-level payload field individually (`PayloadHasher`), salted per record, into a manifest;
the manifest's hash (not the raw payload) is what feeds `contentHash`. A field can then be
redacted — its live value replaced with `"[REDACTED]"` in the mutable `payload` column — without
touching the immutable manifest, salt, or `contentHash`. Verification recombines the manifest: for
a redacted field it trusts the preserved commitment; for everything else it recomputes and
compares. See `PayloadHasher` and `ChainVerificationService` javadoc for the full mechanics.

**Documented limitation:** verification does not — cannot — independently re-check a redacted
field's value, since the plaintext is gone. An attacker with raw database write access could in
principle alter a redacted field's placeholder and its `redactedFields` flag together without
being caught at that layer (see `PayloadHasherTest`
`tamperingWithARedactedFieldsPlaceholderIsNotFlaggedByFieldCheck`). Mitigation: every *legitimate*
redaction appends a companion `PAYLOAD_REDACTED` event to the chain, so a reviewer can cross-check
a record's current `redactedFields` against that trail — a discrepancy is itself a signal. This
doesn't close the gap against a fully adversarial DB-level attacker (nothing purely local to one
record can, against someone who rewrites a record and its neighbors consistently — the same
limitation any hash chain has without external anchoring; see "Bulk export" below).

### Bulk export

`ExportBundle` includes each record's payload manifest and salt (not just display fields), so a
recipient can independently recompute and check every included record's `contentHash` with only
the bundle JSON — no database access. `bundleHash` covers the whole bundle's contents, so any
post-export edit (add/remove/reorder/alter a record) is detectable.

Two things this does **not** prove, by design:
- **Completeness.** A resource/actor-filtered bundle is generally not a contiguous slice of the
  chain — other resources' records can sit between two exported records' sequence numbers. So
  chain-linkage can only be checked for the *first* record in the bundle (against the declared
  `previousHashBeforeBundle`), not record-to-record within the bundle. A malicious exporter could
  omit records without that being detectable from the bundle alone.
- **Authenticity.** `bundleHash` detects accidental corruption or a naive edit, but it is not a
  digital signature — an attacker able to rewrite the bundle file can also recompute and rewrite
  `bundleHash` to match. Real non-repudiation would need the bundle (or its hash) signed with the
  service's private key, so a recipient can check it against a published public key. Out of scope
  here; noted as a limitation.

(An earlier version of `BundleVerificationService` incorrectly checked record-to-record chain
linkage across the whole bundle, which fails on legitimately interleaved data — caught by
`ExportIntegrationTest`, fixed, documented in `AI_USAGE_LOG.md`.)

### Concurrency (known limitation)

`AuditEventService.append()` is `synchronized`: each new record's `previousHash` must link to the
current chain tip, so two concurrent appends must not both read the same tip. This correctly
serializes writes within a single JVM instance, but **does not hold across multiple instances** of
the service. A production deployment handling concurrent writes across instances would need a
DB-level advisory lock (e.g. Postgres `pg_advisory_xact_lock`) or a single-writer/queue
architecture instead.

### Schema management (known limitation)

`spring.jpa.hibernate.ddl-auto=update` lets Hibernate manage the schema for this prototype. A
production service would use versioned migrations (Flyway/Liquibase) instead, so schema changes
are reviewable and repeatable across environments.

### No authentication/authorization (known limitation)

Nothing in this service checks who's calling it. This is fine for a local prototype but is a hard
blocker for production, especially for endpoints that expose or act on sensitive data — redaction,
retention, export, and the compliance report all need to be gated to specific roles before this
could go anywhere near real client account data. Flagged here rather than treated as implicit,
and called out again in `SCENARIO_C.md` since that scenario is explicitly regulator-facing.

### Hashing instants safely (a bug caught during Scenario B, fixed)

`ChainHasher` originally hashed `Instant` fields via string concatenation (`Instant.toString()`).
That's safe for Scenario A's own write→verify path (same JVM, same object), but bulk export
verification recomputes hashes from `Instant`s that have round-tripped through JSON — and
`Instant.toString()` is not guaranteed to produce an identical string for an equal instant across
different serializers. Fixed by hashing `epochSecond`+`nano` directly (what `Instant.equals()`
actually compares), which is representation-independent. Caught by `ExportIntegrationTest` failing
on legitimate, unmodified data before the fix — see `AI_USAGE_LOG.md`.

## Manually inspecting or tampering with the data store

The assignment asks that the service be validated by writing events, querying them, verifying the
chain, then modifying a record directly in the data store and verifying again. `scripts/tamper-demo.sql`
has ready-to-run queries for all three violation types plus inline instructions; the short version
(the automated integration test does the same thing against an in-memory DB):

1. Start the app, write a few events via `POST /audit/events`, confirm `GET /audit/verify` reports
   `intact: true`.
2. Back up `data/auditlog.mv.db` so you can restore between demos instead of re-seeding each time.
3. Open the H2 console at `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/auditlog`,
   user `sa`, blank password) and run an `UPDATE`/`DELETE` from `scripts/tamper-demo.sql` directly
   against `audit_event`, bypassing the API entirely. No need to stop the app first — the console
   runs inside the same JVM and shares the same open database instance, so there's no file-lock
   conflict, and autocommit is on by default.
4. Call `GET /audit/verify` again — it reports `intact: false` with the sequence number and
   violation type.

Alternative when the app isn't running: the H2 command-line shell can edit the file directly
(`java -cp ~/.m2/repository/com/h2database/h2/<version>/h2-<version>.jar org.h2.tools.Shell -url
"jdbc:h2:file:./data/auditlog" -user sa -password "" -sql "..."`), since nothing else holds the
file lock while the app is stopped.
