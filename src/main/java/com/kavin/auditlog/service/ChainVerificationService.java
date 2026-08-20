package com.kavin.auditlog.service;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import com.kavin.auditlog.web.dto.ChainVerificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChainVerificationService {

    public enum Violation {
        CONTENT_HASH_MISMATCH,
        PREVIOUS_HASH_MISMATCH
    }

    private final AuditEventRepository repository;
    private final ChainHasher chainHasher;

    public ChainVerificationService(AuditEventRepository repository, ChainHasher chainHasher) {
        this.repository = repository;
        this.chainHasher = chainHasher;
    }

    /**
     * Walks the full chain in sequence order, including archived records (Scenario B):
     * archiving must not change stored hash fields, so archived rows still verify cleanly.
     * For each record:
     * <ol>
     *   <li>recompute contentHash from the stored fields and compare to the stored contentHash
     *       — catches direct tampering with a record's own fields</li>
     *   <li>compare stored previousHash to the prior record's contentHash (GENESIS_HASH for
     *       the first record) — catches deleted, reordered, or substituted records</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public ChainVerificationResponse verify() {
        List<AuditEvent> chain = repository.findAllByOrderBySequenceNumberAsc();

        String expectedPreviousHash = ChainHasher.GENESIS_HASH;
        long checked = 0;

        for (AuditEvent event : chain) {
            checked++;

            String recomputedContentHash = chainHasher.hashContent(
                    event.getSequenceNumber(), event.getEventType(), event.getActorId(), event.getResourceType(),
                    event.getResourceId(), event.getPayload(), event.getOccurredAt(), event.getRecordedAt());

            if (!recomputedContentHash.equals(event.getContentHash())) {
                return ChainVerificationResponse.broken(checked, event.getSequenceNumber(),
                        Violation.CONTENT_HASH_MISMATCH.name(),
                        "stored content_hash does not match a hash recomputed from the record's current field values "
                                + "- the record was modified after it was written");
            }

            if (!expectedPreviousHash.equals(event.getPreviousHash())) {
                return ChainVerificationResponse.broken(checked, event.getSequenceNumber(),
                        Violation.PREVIOUS_HASH_MISMATCH.name(),
                        "stored previous_hash does not match the preceding record's content_hash "
                                + "- a record was inserted, deleted, reordered, or substituted");
            }

            expectedPreviousHash = event.getContentHash();
        }

        return ChainVerificationResponse.intact(checked);
    }
}
