package org.qubership.integration.platform.engine.persistence.shared.repository;

import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.qubership.integration.platform.engine.persistence.shared.entity.IdempotencyRecord;

@ApplicationScoped
public class IdempotencyRecordRepository implements PanacheRepositoryBase<IdempotencyRecord, String> {
    @Inject
    @PersistenceUnit("checkpoints")
    EntityManager em;

    public boolean existsByKeyAndNotExpired(String key) {
        String sql = """
            select
                count(r) > 0
            from
                engine.idempotency_records r
            where
                r.key = :key
                and r.expires_at >= now()
        """;
        Query query = em.createNativeQuery(sql);
        query.setParameter("key", key);
        Object result = query.getSingleResult();
        if (result instanceof Boolean b) {
            return b;
        } else if (result instanceof Number n) {
            return n.intValue() == 1;
        } else {
            throw new IllegalStateException(
                    "Unexpected result type for boolean native query: "
                            + result.getClass().getName());
        }
    }

    public int insertIfNotExistsOrUpdateIfExpired(String key, String data, int ttl) {
        String sql = """
            insert into
                    engine.idempotency_records as r
                        (key, data, created_at, expires_at)
                values (
                    :key,
                    :data ::json,
                    now(),
                    now() + make_interval(secs => :ttl)
                )
                on conflict (key) do update
                    set
                        data = :data ::json,
                        created_at = now(),
                        expires_at = now() + make_interval(secs => :ttl)
                    where
                        r.expires_at < now()
        """;
        Query query = em.createNativeQuery(sql, IdempotencyRecord.class);
        query.setParameter("key", key);
        query.setParameter("data", data);
        query.setParameter("ttl", ttl);
        return query.executeUpdate();
    }

    public int deleteExpired() {
        String sql = """
            delete from engine.idempotency_records r where r.expires_at < now()
        """;
        Query query = em.createNativeQuery(sql, IdempotencyRecord.class);
        return query.executeUpdate();
    }

    public int deleteByKeyAndNotExpired(String key) {
        String sql = """
            delete from
                    engine.idempotency_records r
                where
                    r.key = :key
                    and r.expires_at >= now()
        """;
        Query query = em.createNativeQuery(sql, IdempotencyRecord.class);
        query.setParameter("key", key);
        return query.executeUpdate();
    }
}
