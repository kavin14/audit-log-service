# AI Usage Log

Tool: Claude Code (Anthropic), interactive CLI agent with file/shell access. Model: Claude Sonnet 5.

This log is written as work happens, not reconstructed afterward. Each entry: what was asked,
what the AI produced, what a human (the candidate) needs to review/decide, and anything the AI
got wrong that had to be caught and fixed. **Entries marked "pending review" have not yet been
read line-by-line and explicitly approved by the candidate** — see the note at the bottom.

## 2026-08-20 — Environment setup

- Prompted: set up a local Java/Maven dev environment (machine had neither) and scaffold a Spring
  Boot project via Spring Initializr.
- AI action: installed JDK 21 (Temurin) and Maven 3.9.9 into `~/.local/tools` (no sudo/Homebrew
  available), added them to `~/.zshrc`. Generated the project via `start.spring.io` with
  web/data-jpa/h2/validation/lombok.
- Caught and fixed: Spring Initializr's generated `pom.xml` specified
  `spring-boot-starter-parent` version `4.1.0.RELEASE`, which doesn't exist on Maven Central for
  Spring Boot 4 (the `.RELEASE` suffix convention was dropped) — confirmed the real artifact
  version (`4.1.0`) via the Maven Central metadata XML and corrected the pom.
- Human review needed: none — mechanical scaffold, verified by a clean `mvn compile` + `mvn test`.

## 2026-08-20 — Domain model and hash chain design

- Prompted: design and implement the tamper-evident hash chain per the assignment's Core
  Requirements (§4) and Scenario A (§5) — write API, query API, per-record content hash + link to
  previous record, chain verification endpoint.
- AI action: proposed and implemented:
  - `AuditEvent` entity with `sequenceNumber`-based chain order (not the caller-supplied
    timestamp), separate `occurredAt`/`recordedAt` fields.
  - `CanonicalJson`: deterministic (sorted-key) JSON serialization of the payload, so hashing is
    stable across DB round-trips.
  - `ChainHasher`: SHA-256 content hash with an ASCII SOH (0x01) field separator to prevent
    field-boundary ambiguity; `GENESIS_HASH` constant for the first record.
  - `ChainVerificationService`: walks the chain in order, recomputes each content hash, checks
    `previousHash` linkage, reports the first broken record and violation type.
- Design rationale (AI-proposed, not yet challenged by a human reviewer): a synchronized append
  method to serialize chain-tip reads/writes within one instance, documented as a single-instance
  limitation rather than solved with distributed locking, since that's out of scope for a
  prototype. **This trade-off should be examined and either endorsed or revised before the live
  defense** — it's the kind of "risk/trade-off" call §4.5 of the assignment expects the candidate
  to own.
- Caught and fixed (AI's own errors, found via compile/test failures, not by a human):
  - `CanonicalJson` was first written against Jackson 2 (`com.fasterxml.jackson.databind.*`,
    `MapperFeature.ORDER_MAP_ENTRIES_BY_KEYS`). Spring Boot 4 ships Jackson 3
    (`tools.jackson.*`), and that feature moved to `SerializationFeature`. Confirmed via
    `javap` against the actual jar in `~/.m2`, not from memory/documentation.
  - `TestRestTemplate` doesn't exist under its old package in Spring Boot 4 test scope; it moved
    to `org.springframework.boot.resttestclient.TestRestTemplate` in a new
    `spring-boot-resttestclient` module, which also needed `spring-boot-restclient` added
    explicitly (its autoconfiguration references `RestTemplateBuilder`, which isn't pulled in
    transitively otherwise) and `@AutoConfigureTestRestTemplate` on the test class.
  - Integration test initially failed intermittently depending on JUnit method execution order:
    all three test methods shared one Spring context (and thus one in-memory H2 instance), so the
    tampering test's raw-SQL corruption leaked into the other tests' shared chain. Fixed with
    `@DirtiesContext(methodMode = AFTER_METHOD)` on the tampering test so it gets an isolated
    context.
- Verification performed:
  - `mvn test`: 12/12 passing, including the automated tamper-detection integration test.
  - Manually re-ran the same flow against the real app (not just the test's in-memory DB): started
    the app, wrote two events via `curl`, confirmed `/audit/verify` reported `intact: true`;
    stopped the app; edited `actor_id` on a stored row directly via the H2 command-line shell
    (bypassing the API); restarted; confirmed `/audit/verify` reported
    `firstBrokenSequenceNumber: 1`, `violationType: CONTENT_HASH_MISMATCH`.
- Human review needed (not yet done): read `AuditEvent`, `ChainHasher`, `CanonicalJson`,
  `AuditEventService`, `ChainVerificationService` line by line. Confirm the hash-chain design,
  the field-separator approach, and the timestamp split (`occurredAt` vs `recordedAt`) all make
  sense and can be explained/defended without notes. Decide whether the synchronized-append
  concurrency trade-off is acceptable to present as-is or should be reworked.

## 2026-08-20 — Scenario B: redaction (payload hashing redesign)

- Prompted: implement Scenario B's redaction requirement — "the original hash covers the original
  value, so simply removing the value would invalidate the hash... design and implement a
  redaction scheme that satisfies both tamper-evidence and data privacy."
- Design rationale (AI-proposed): rather than patch the existing whole-payload hash, reworked it
  to a per-field manifest (`PayloadHasher`) — each payload field gets its own salted commitment
  hash; the manifest's hash feeds `contentHash` instead of the raw payload text. A field can be
  redacted (value replaced with a placeholder) without touching the manifest, so `contentHash`
  never changes. This is a deliberate, considered redesign (documented in the commit message and
  in `README.md` "Redaction"), not a quick patch — **this is the single design decision most
  worth independently re-deriving and defending in the live session**, since it's the assignment's
  explicitly-called-out "genuine engineering problem."
- Explicitly documented, not hidden, limitation: verification cannot independently re-check a
  redacted field's value once redacted (the plaintext is gone). Mitigation implemented: every
  redaction appends a companion `PAYLOAD_REDACTED` audit event, so the act of redacting is itself
  part of the tamper-evident trail. `PayloadHasherTest` includes a test
  (`tamperingWithARedactedFieldsPlaceholderIsNotFlaggedByFieldCheck`) whose whole purpose is to
  make this limitation visible and explicit rather than silently true.
- Verification performed: `PayloadHasherTest` (5 tests) unit-level; `RedactionIntegrationTest`
  (3 tests) end-to-end via real HTTP calls, including tampering with a *non*-redacted field after
  an unrelated redaction to confirm the rest of the record is still fully protected.
- Human review needed: read `PayloadHasher.recombine` and confirm you can explain, without notes,
  why redacting field A doesn't affect verifiability of field B, and why the companion-event
  mitigation is a mitigation and not a fix.

## 2026-08-20 — Scenario B: retention and bulk export

- Retention: straightforward relative to redaction — `RetentionService` only ever flips
  `archived`/`archivedAt`, never a hash-relevant field, so no new hashing design was needed. Low
  review priority relative to redaction/export.
- Bulk export: implemented `ExportBundle` (records + `bundleHash`) and `BundleVerificationService`
  to make "a recipient can independently verify records haven't been altered since export"
  concretely demonstrable (a `/audit/export/verify` endpoint that only looks at the bundle JSON).
- **Two real bugs, both caught by `ExportIntegrationTest` failing on legitimate, non-tampered
  data (not found by manual review) — both are worth understanding, not just accepting the fix:**
  1. `ChainHasher` hashed `Instant` fields via `toString()`. That's fine when hash and re-verify
     happen against the same in-memory object (Scenario A's normal path), but bundle verification
     recomputes hashes from `Instant`s that round-tripped through JSON first, and
     `Instant.toString()` isn't guaranteed to produce an identical string for an equal instant
     across different serializers/paths. Fixed by hashing `epochSecond`+`nano` directly (what
     `Instant.equals()` actually compares) — representation-independent by construction. Applied
     the same fix to `bundleHash`'s `exportedAt` field.
  2. `BundleVerificationService` initially checked every exported record's `previousHash` against
     the *previous exported record's* `contentHash` — copying the full-chain verifier's logic
     without noticing the assumption doesn't hold: a resource/actor-filtered bundle is generally
     **not** a contiguous chain slice (other resources' records can sit between two exported
     records). This made the check fail on a perfectly legitimate export the moment an unrelated
     record happened to be interleaved. Fixed to only check chain-linkage for the first record
     (against `previousHashBeforeBundle`); documented the resulting scope of what a bundle proves
     and doesn't in the class javadoc and in `README.md` "Bulk export".
- Verification performed: full suite (25 tests at that point) run three consecutive times after
  both fixes to rule out flakiness, not just a single green run.
- Human review needed: bug #2 in particular is a genuine design-scope question (what *can* a
  filtered bundle prove?), not just a bugfix — be ready to explain the distinction between
  "internally consistent + unaltered since export" and "complete" or "authentic" without reading
  from the README.

## 2026-08-20 — Scenario C: ambiguous requirement

- Prompted: Scenario C's deliberately underspecified requirement ("Regulators need to be able to
  audit access to client account data") — demonstrate clarification before implementation.
- AI action: wrote `SCENARIO_C.md` identifying five ambiguities (does "access" mean reads, writes,
  or both; how broad is "client account data"; who consumes this and how; what does "audit" mean
  as a deliverable; is instrumenting other systems in scope), stated assumptions in place of
  asking a product owner, derived a clarified requirement statement, then implemented against it.
- **This is the one artifact in this repo that most directly represents a judgment call rather
  than an engineering derivation** — a different, equally reasonable reading of the requirement
  (e.g. "access" meaning writes only, or scope including transactions/documents) would produce a
  different implementation. Reviewing `SCENARIO_C.md` and deciding whether these are the
  assumptions you'd have made is higher-priority than reviewing the report endpoint's code itself.
- Implementation was deliberately kept thin (a reporting layer over Scenario A's existing
  query/verification, not new storage) once the requirement was clarified — no new design
  decisions of the weight of Scenario B's redaction rework were needed here.
- Verification performed: `ComplianceReportIntegrationTest` (3 tests) — JSON and CSV output,
  chain-status flag, required-parameter validation.

## Outstanding (not yet started)

- Nothing — all three scenarios and the final summary are implemented as of this log entry. See
  `SUMMARY.md` for what's still a known limitation vs. what's genuinely done.

## Note on review status

Everything above reflects a **first pass generated with heavy AI assistance in a single session**.
Per the assignment's integrity expectations (§0.3), the candidate is expected to be able to
explain and defend every part of this, not just accept it. Before submission:

1. Read every file, not just this log.
2. For anything you wouldn't have written this way yourself, either change it to something you
   understand and agree with, or be ready to explain *why* you kept it and what you'd do
   differently with more time.
3. Update this log's entries from "pending review" to reflect what you actually changed, rejected,
   or endorsed, and why — that's the traceability the assignment is asking for, and it's also what
   the live defense will probe.
