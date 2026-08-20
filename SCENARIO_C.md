# Scenario C: Compliance Reporting

## The requirement as given

> Product says: "Regulators need to be able to audit access to client account data."

## Ambiguities identified

1. **"Access" — read-only views, or every interaction?** A regulator investigating a potential
   privacy breach cares about *who looked at* a record, not just who changed it. "Access" reads
   more naturally as "any interaction," but the service so far (Scenario A) only demonstrates
   write events (`USER_LOGIN`, `RECORD_UPDATED`). Nothing currently guarantees that *read* access
   gets logged at all.
2. **"Client account data" — how broad?** Scoped to the `ACCOUNT` resource type specifically, or
   does it include everything touching a client (transactions, documents, support tickets)?
3. **Who consumes this, and how?** A regulator filing a formal request, an internal compliance
   officer running routine checks, or an automated regulatory-reporting pipeline are three very
   different consumers with different format/authentication/volume needs.
4. **What does "audit" mean as a deliverable?** A query API a compliance officer's tooling calls,
   a downloadable report for a specific request, both, or a live dashboard?
5. **Is logging access this service's responsibility, or does something else need to change?**
   This service can store and report on access events, but it cannot *make* the account-viewing
   system emit them - that's a separate integration.

## Assumptions made (and what I'd ask instead, given the chance)

| # | Assumption | Question I'd actually ask a product owner |
|---|---|---|
| 1 | "Access" means both reads and writes, distinguished by `eventType` convention (e.g. `ACCOUNT_VIEWED` vs `ACCOUNT_UPDATED`), not a new field. | "When you say audit access, do regulators need to see who *viewed* data, or only who *changed* it?" |
| 2 | Scoped to `resourceType=ACCOUNT` for this implementation. Extending to other resource types is the same pattern, not a new design. | "Is this specifically about account records, or client data more broadly?" |
| 3 | The consumer is a compliance/reporting tool calling an API, so the deliverable is a query endpoint - not a dashboard or a one-off manual export. CSV is also offered since regulatory deliverables commonly need to be handed over as a file. | "Who or what actually calls this - a person, or another system?" |
| 4 | Instrumenting the account-viewing system to call this service's write API on every access is **out of scope** here. This service can only report on what it's told about. | "Is the account-viewing system already planning to call the audit log on every read, or does that still need to be built?" |
| 5 | No authentication/authorization is added specifically for this endpoint, consistent with the rest of the prototype having none. Flagged as a must-fix-before-production gap, not unique to Scenario C. | (Not something I'd ask - this is a scope statement, not a real open question: nothing in this codebase has auth, and this endpoint is not exempt from that gap.) |

## Clarified requirement

> Compliance and regulatory users need to retrieve every access - read or write - to a given
> client account's data over a specified date range: who accessed it, when, and what was
> accessed or changed, sourced from the existing tamper-evident audit log, in a format usable
> both by internal tooling (JSON) and for a regulatory filing (CSV) - without needing to know the
> underlying event API's generic filter parameters. The report should also state whether the
> underlying audit chain is currently intact, since a report a regulator relies on is only as
> trustworthy as the log it's drawn from.

## Design

`GET /audit/compliance-report?resourceId=&from=&to=&resourceType=ACCOUNT&format=json|csv`

- Wraps the existing `AuditEventService.query()` (Scenario A) - no new storage or hashing
  concerns; this is a presentation layer over data that's already tamper-evident.
- `resourceType` defaults to `ACCOUNT` (the clarified scope) but is overridable, since the same
  reporting shape works for any resource type without new code.
- Each row: `sequenceNumber`, `eventType`, `actorId`, `occurredAt`, `recordedAt`, and a short
  human-readable `summary` derived from the payload (not the raw JSON - a regulator reading a CSV
  shouldn't need to parse nested JSON by hand).
- The response includes a `chainIntact` boolean (reusing `ChainVerificationService.verify()`) so
  the report is explicit about whether the underlying log's integrity currently holds, rather than
  silently implying a guarantee it can't back up if the chain were ever found broken.
- `includeArchived` defaults to `true` here (unlike the general query API's default of `false`) -
  a compliance report that silently dropped archived history would defeat the purpose.

## What's implemented vs. scoped out

**Implemented:** the report endpoint (JSON and CSV), scoped to one resource at a time, with the
chain-integrity flag.

**Scoped out, deliberately:**
- Instrumenting other systems to actually emit `*_VIEWED` access events - an integration task for
  those systems, not this service.
- Authentication/authorization on this or any endpoint - a known gap across the whole prototype
  (see README "Known limitations"), not specific to compliance reporting, but worth calling out
  again here since this endpoint specifically handles regulator-facing sensitive output.
- Reporting across multiple resources/accounts in one call (e.g. "all accounts a given employee
  accessed this month") - the existing filtered query API (`GET /audit/events`) already supports
  broader filter combinations; the compliance report endpoint is intentionally narrower and more
  opinionated about its output shape for the single-resource case that's most clearly implied by
  the requirement as given.
