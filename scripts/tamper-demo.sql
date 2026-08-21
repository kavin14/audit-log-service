-- Tamper-evidence demo queries for the audit log service.
--
-- The assignment asks that the service be validated by writing events, verifying the chain,
-- then modifying a record directly in the data store and verifying again to confirm detection.
-- This script is the "modifying a record directly" part.
--
-- How to run these:
--   1. Start the app and write a few events via POST /audit/events (or the Postman collection).
--   2. GET /audit/verify - confirm intact:true first, so you have a clean baseline.
--   3. Back up the data file so you can restore between demos instead of re-seeding each time:
--        cp data/auditlog.mv.db data/auditlog.mv.db.bak
--   4. Open http://localhost:8080/h2-console in a browser. JDBC URL: jdbc:h2:file:./data/auditlog,
--      user sa, blank password, Connect. This is the SAME running JVM as the app (H2 shares one
--      in-memory database instance per file path within a process), so there is no file-lock
--      conflict and no need to stop the app first. Autocommit is on by default.
--   5. Run ONE section below, then GET /audit/verify again to see the violation reported.
--   6. Before trying a different section, restore the backup (stop the app, copy auditlog.mv.db.bak
--      back over auditlog.mv.db, restart) - verify() reports only the FIRST break it finds, so a
--      second untouched tamper further down the chain won't show up until the first is fixed.

-- Inspect the chain first
SELECT sequence_number, event_type, actor_id, resource_id, payload, redacted_fields,
       content_hash, previous_hash, archived
FROM audit_event
ORDER BY sequence_number;


-- ============================================================================
-- 1) PAYLOAD_FIELD_TAMPERED - edit a live, non-redacted payload value directly.
--    Adjust the literal to match a value that actually appears in that row's payload
--    (check the SELECT above first).
-- ============================================================================
UPDATE audit_event
SET payload = REPLACE(payload, '250', '999999')
WHERE sequence_number = 1;
-- GET /audit/verify -> intact:false, violationType=PAYLOAD_FIELD_TAMPERED, names the field.


-- ============================================================================
-- 2) CONTENT_HASH_MISMATCH - two ways to trigger it:
-- ============================================================================

-- 2a) Change a non-payload field that feeds directly into contentHash.
UPDATE audit_event SET actor_id = 'mallory' WHERE sequence_number = 2;

-- 2b) Tamper with the payload's hash manifest itself, not the live payload -
--     this is the case check #1 (above) can't catch on its own; contentHash recomputation
--     is the backstop that catches it.
-- UPDATE audit_event
-- SET payload_field_hashes = REPLACE(payload_field_hashes, 'a', 'b')
-- WHERE sequence_number = 2;

-- GET /audit/verify -> intact:false, violationType=CONTENT_HASH_MISMATCH.


-- ============================================================================
-- 3) PREVIOUS_HASH_MISMATCH - delete a record entirely.
--    Detection surfaces one sequence number DOWNSTREAM of the delete (the deleted record
--    itself can't be "caught" since it no longer exists to check) - see the record right
--    after this one for firstBrokenSequenceNumber.
-- ============================================================================
DELETE FROM audit_event WHERE sequence_number = 3;
-- GET /audit/verify -> intact:false, violationType=PREVIOUS_HASH_MISMATCH.


-- ============================================================================
-- Restore between demos (run from a terminal, not the H2 console):
--   stop the app
--   cp data/auditlog.mv.db.bak data/auditlog.mv.db
--   restart the app
-- ============================================================================
