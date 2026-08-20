package com.kavin.auditlog.service;

import com.kavin.auditlog.config.RetentionProperties;
import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scenario B: retention. Archiving only ever flips {@code archived}/{@code archivedAt} - it
 * never touches any field that participates in a record's contentHash, so archived records keep
 * verifying cleanly (see ChainVerificationService, which walks archived records too). "Archive",
 * not "delete": this is a tamper-evident log, so removing a record's chain-relevant fields
 * entirely would either break verification for everything after it, or require re-deriving the
 * whole chain from that point forward. Soft-archival sidesteps that; true deletion of very old
 * archived data (e.g. for storage cost) is a separate, harder problem intentionally out of scope
 * here - see ARCHITECTURE.md "Known limitations".
 */
@Service
public class RetentionService {

    public record ArchiveResult(int archivedCount, int windowDays, Instant cutoff) {
    }

    private final AuditEventRepository repository;
    private final RetentionProperties properties;

    public RetentionService(AuditEventRepository repository, RetentionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public ArchiveResult archiveEligible(Integer overrideDays) {
        int windowDays = overrideDays != null ? overrideDays : properties.getArchiveAfterDays();
        if (windowDays < 0) {
            throw new IllegalArgumentException("olderThanDays must be >= 0");
        }
        Instant cutoff = Instant.now().minusSeconds(windowDays * 86400L);

        List<AuditEvent> eligible = repository.findByArchivedFalseAndRecordedAtBefore(cutoff);
        Instant now = Instant.now();
        eligible.forEach(event -> event.archive(now));
        repository.saveAll(eligible);

        return new ArchiveResult(eligible.size(), windowDays, cutoff);
    }
}
