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
                insert into
                    engine.idempotency_records as r
                        (key, data, created_at, expires_at)
                values (
                    :key,
                    :data ::json,
                    now(),
                    date_add(now(), make_interval(secs => :ttl))
                )
                on conflict (key) do update
                    set
                        data = :data ::json,
                        created_at = now(),
                        expires_at = date_add(now(), make_interval(secs => :ttl))
                    where
                        r.expires_at < now()
            """
    )
    int insertIfNotExistsOrUpdateIfExpired(String key, String data, int ttl);

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
                delete from
                    engine.idempotency_records r
                where
                    r.key = :key
                    and r.expires_at >= now()
            """
    )
    int deleteByKeyAndNotExpired(String key);
}
