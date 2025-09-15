/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.persistence.shared.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.qubership.integration.platform.engine.persistence.shared.entity.SessionInfo;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SessionInfoRepository implements PanacheRepositoryBase<SessionInfo, String> {
    @Inject
    EntityManager em;

    public List<SessionInfo> findAllById(List<String> ids) {
        return list("id IN ?1", ids);
    }

    public List<SessionInfo> findAllByChainIdAndExecutionStatus(String chainId, ExecutionStatus status) {
        return list("chainId = ?1 and executionStatus = ?2", chainId, status);
    }

    public void deleteAllRelatedSessionsAndCheckpoints(String sessionId) {
        String sql = """
            WITH RECURSIVE to_root_node (id, original_session_id) AS (
                SELECT s1.id, s1.original_session_id
                FROM engine.sessions_info s1
                WHERE s1.id = :sessionId

                UNION ALL

                SELECT s2.id, s2.original_session_id
                FROM engine.sessions_info s2
                     JOIN to_root_node rn ON rn.original_session_id = s2.id
            )
            DELETE FROM engine.sessions_info
            WHERE id = (SELECT id FROM to_root_node WHERE original_session_id IS NULL LIMIT 1);
        """;
        Query query = em.createNativeQuery(sql);
        query.setParameter("sessionId", sessionId);
        query.executeUpdate();
    }

    public Optional<SessionInfo> findOriginalSessionInfo(String sessionId) {
        String sql = """
            with recursive session_info as (
                select s1.*
                    from engine.sessions_info s1
                    where s1.id = :sessionId
                union all
                select s2.*
                    from engine.sessions_info s2
                    join session_info si on s2.id = si.original_session_id
            )
            select * from session_info i
                where i.id != :sessionId and i.original_session_id is null
                limit 1;
        """;
        Query query = em.createNativeQuery(sql, SessionInfo.class);
        query.setParameter("sessionId", sessionId);
        return query.getResultStream().findFirst();
    }

    /**
     * Remove old records for a scheduled cleanup task
     *
     * @param olderThan interval string, for example, '1 hour', '7 days', '2 years 3 month'
     */
    public void deleteOldRecordsByInterval(String olderThan) {
        String sql = """
            DELETE FROM
                engine.sessions_info s1
            WHERE
                s1.started < now() - ( :olderThan )::interval
                AND s1.original_session_id IS NULL;
        """;
        Query query = em.createNativeQuery(sql, SessionInfo.class);
        query.setParameter("olderThan", olderThan);
        query.executeUpdate();
    }
}
