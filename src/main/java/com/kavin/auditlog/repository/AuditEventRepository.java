package com.kavin.auditlog.repository;

import com.kavin.auditlog.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    /** Used when appending: the tip of the chain to link the new record's previousHash to. */
    Optional<AuditEvent> findTopByOrderBySequenceNumberDesc();

    /** Used by chain verification: the full chain in order, archived records included. */
    List<AuditEvent> findAllByOrderBySequenceNumberAsc();
}
