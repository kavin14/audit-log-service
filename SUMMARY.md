# Final Engineering Summary

## Plan and rationale

Built incrementally in the order the assignment scopes it: Scenario A (core hash-chained log)
first, since B and C both depend on it; then B (retention, redaction, export), since redaction
in particular forced a redesign of the hashing scheme that everything downstream needed to use;
then C (the ambiguous requirement), implemented as a thin reporting layer over what already
existed rather than a new subsystem. Each capability was built, tested, and committed as its own
step — see `git log` for the actual sequence, which reflects genuine incremental development, not
a single dump.

Design priority throughout: correctness and defensibility of the core tamper-evidence property
over breadth of feature surface. The hash-chain design was reworked once (Scenario A's per-blob
payload hash → Scenario B's per-field manifest) rather than bolting redaction on top of a design
that couldn't support it — see `README.md` "Redaction" for why the original approach couldn't
just be patched.

## Artifacts

- `README.md` — architecture, API reference, setup/run/test instructions, known limitations.
- `SCENARIO_C.md` — the clarification process for the ambiguous requirement: ambiguities
  identified, assumptions made, clarified requirement statement, design, scope boundary.
- `AI_USAGE_LOG.md` — traceability log written as work happened, including AI-introduced bugs
  that were caught and fixed.
- `ATTESTATION.md` — template per §0.4; name/dates left for the candidate to fill in personally.
- Source: `src/main/java/com/kavin/auditlog/` — `domain` (entity), `repository` (Spring Data +
  Specifications), `service` (hashing, business logic), `web` (controllers, DTOs), `config`,
  `exception`.
- Tests: `src/test/java/...` — 28 tests across unit (hashing/canonicalization primitives) and
  integration (one suite per scenario/capability, hitting real HTTP endpoints against a real,
  if in-memory, database).

## Risks, trade-offs, and validation

| Area | Decision | Risk / trade-off | Validated by |
|---|---|---|---|
| Concurrency | `synchronized` append within one instance | Doesn't hold across multiple instances (needs DB advisory lock or single-writer in production) | Documented; not load-tested (out of scope for a prototype) |
| Schema | Hibernate `ddl-auto=update` | Not reviewable/repeatable like migrations | Acceptable for prototype scope; flagged for production |
| Redaction | Per-field manifest, trust-on-redact | Can't independently re-verify a redacted field's value; a DB-level attacker could forge a redacted field's placeholder alongside its flag | `PayloadHasherTest`, mitigated (not solved) by a companion audit event per redaction |
| Bulk export | bundleHash, not a signature | Detects post-export tampering, not forged authenticity; bundle isn't provably complete | `ExportIntegrationTest`; limitation documented, real fix (signing) scoped out |
| Auth | None anywhere in the service | Blocks production use, especially for B/C endpoints touching sensitive data | Explicitly flagged, not silently assumed away |
| Instant hashing | epochSecond+nano, not `toString()` | N/A — this *is* the fix for a real bug the JSON round-trip surfaced | `ExportIntegrationTest` caught the original bug; now passing consistently across repeated runs |
| Bundle chain-linkage | Only first-record linkage checked | Initial implementation incorrectly checked every record — fixed after test failure | `ExportIntegrationTest` with genuinely interleaved records |

## Assumptions

- Single-instance deployment for this prototype (documented consequence: the concurrency
  trade-off above).
- `resourceType=ACCOUNT` is the right default scope for Scenario C's compliance report, per the
  clarified requirement in `SCENARIO_C.md` — overridable, not hardcoded.
- "Access" for Scenario C means both reads and writes, distinguished by `eventType` naming
  convention, not a new schema field.
- No existing production traffic/data to migrate — this is greenfield, so `ddl-auto=update` and
  the absence of migrations carries no real risk yet.

## Limitations (consolidated)

1. No authentication/authorization anywhere (see README).
2. Concurrency correctness holds only within a single JVM instance (see README "Concurrency").
3. Schema managed by Hibernate, not versioned migrations (see README "Schema management").
4. Redaction cannot be independently re-verified once applied; mitigated, not solved, by a
   companion audit trail (see README "Redaction").
5. Export bundles are tamper-evident but not cryptographically signed, and not provably complete
   for a filtered subset (see README "Bulk export").
6. True deletion (vs. archival) of very old records is unsupported and would be a genuinely
   harder problem for a hash-chained log — intentionally out of scope (see
   `RetentionService` javadoc).
7. Scenario C's report covers one resource per call by design; broader queries already exist via
   `GET /audit/events`.

## What I'd do next with more time

- Add authentication/authorization (even a minimal role check) before touching anything else,
  since it blocks every other concern from mattering in production.
- Replace `ddl-auto=update` with Flyway migrations.
- Prototype the DB-advisory-lock approach for multi-instance write concurrency.
- Add a real signing step for export bundles (sign `bundleHash` with a service key).
