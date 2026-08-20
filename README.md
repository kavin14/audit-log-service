# Audit Log Service

A tamper-evident, append-only audit log service. Each record is chained to the one before it
via a SHA-256 content hash, so any modification to a past record — or any deletion, reordering,
or substitution — is detectable by walking the chain.

Built for the Charles Schwab / Persistent take-home assignment ("Interview Assignment: Build an
AI-Assisted Software Engineering System — Audit Log Service", v2.0). See `AI_USAGE_LOG.md` for
AI-assistance traceability and `ATTESTATION.md` for the required attestation.

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

Unit tests cover the canonical-JSON serialization and content-hashing primitives. The integration
test (`AuditLogApiIntegrationTest`) exercises the full validation flow the assignment describes:
write events via the API, query them, verify an intact chain, then tamper with a stored row via
raw SQL (bypassing the API entirely) and confirm `/audit/verify` reports the break.

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

## Architecture

### Hash chain

Each record stores:
- `contentHash` — SHA-256 of the record's own fields (sequenceNumber, eventType, actorId,
  resourceType, resourceId, canonical payload JSON, occurredAt, recordedAt), joined with the
  ASCII SOH control character (0x01) as a field separator. This rules out field-boundary
  ambiguity (e.g. `actorId="a"` + `resourceType="bc"` hashing identically to `actorId="ab"` +
  `resourceType="c"`) without needing a full canonical envelope for the whole record.
- `previousHash` — the prior record's `contentHash`, or 64 zero characters (`GENESIS_HASH`) for
  the first record.

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

## Manually inspecting or tampering with the data store

The assignment asks that the service be validated by writing events, querying them, verifying the
chain, then modifying a record directly in the data store and verifying again. To do this by hand
(the automated integration test does the same thing against an in-memory DB):

1. Start the app, write a few events via `POST /audit/events`, confirm `GET /audit/verify` reports
   `intact: true`.
2. Stop the app (H2's embedded file mode holds an exclusive lock on the `.mv.db` file).
3. Use the H2 command-line shell to edit a row directly, bypassing the API entirely:
   ```
   java -cp ~/.m2/repository/com/h2database/h2/<version>/h2-<version>.jar org.h2.tools.Shell \
     -url "jdbc:h2:file:./data/auditlog" -user sa -password "" \
     -sql "UPDATE audit_event SET actor_id = 'attacker' WHERE sequence_number = 1;"
   ```
4. Restart the app and call `GET /audit/verify` again — it reports `intact: false` with the
   sequence number and violation type.
