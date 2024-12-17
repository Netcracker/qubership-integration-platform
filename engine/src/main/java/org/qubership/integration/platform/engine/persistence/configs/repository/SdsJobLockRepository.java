package org.qubership.integration.platform.engine.persistence.configs.repository;

import org.qubership.integration.platform.engine.persistence.configs.entity.SdsJobLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.sql.Timestamp;
import java.util.Collection;

public interface SdsJobLockRepository extends JpaRepository<SdsJobLock, String> {
    SdsJobLock findByJobId(String jobId);

    boolean existsByJobId(String jobId);

    void deleteAllByJobIdAndCreatedLessThanEqual(String jobId, Timestamp olderThan);

    void deleteAllByJobId(String jobId);

    void deleteAllByExecutionId(String executionId);

    void deleteAllByJobIdIn(Collection<String> jobId);

}
