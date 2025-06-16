package org.qubership.integration.platform.engine.persistence.shared.repository;

import org.qubership.integration.platform.engine.persistence.shared.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
    @Query(
            nativeQuery = true,
            value = """
                select
                    count(r) > 0
                from
                    engine.idempotency_records r
                where
                    r.key = :key
                    and r.expires_at >= now()
            """
    )
    boolean existsByKeyAndNotExpired(String key);

    @Modifying
    @Query(
            nativeQuery = true,
            value = """
                delete from engine.idempotency_records r where r.expires_at < now()
            """
    )
    void deleteExpired();

    @Modifying
    @Query(
            nativeQuery = true,
            value = """
                with deleted as (
                    delete from
                        engine.idempotency_records r
                    where
                        r.key = :key
                        and r.expires_at >= now()
                    returning r.key
                ) select count(*) from deleted
            """
    )
    long deleteByKeyAndNotExpired(String key);
}
