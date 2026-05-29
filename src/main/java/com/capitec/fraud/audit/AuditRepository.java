package com.capitec.fraud.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntity, Long> {
    Page<AuditEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
