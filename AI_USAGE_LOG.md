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

## Outstanding (not yet started)

- Scenario B (retention/archival, redaction, bulk export).
- Scenario C (ambiguous compliance-reporting requirement — clarification + design + implementation).
- Final Engineering Summary.

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
