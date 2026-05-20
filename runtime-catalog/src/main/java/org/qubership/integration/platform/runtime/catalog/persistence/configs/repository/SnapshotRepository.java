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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository;

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.SnapshotBaseRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface SnapshotRepository extends SnapshotBaseRepository {
    @Query(nativeQuery = true,
            value = """
                    DELETE FROM {h-schema}snapshots where ctid in 
                    (SELECT s.ctid 
                    FROM {h-schema}snapshots s 
                    LEFT JOIN {h-schema}deployments d ON d.snapshot_id = s.id 
                    LEFT JOIN {h-schema}chains c ON c.current_snapshot_id = s.id 
                    WHERE s.created_when < :createdWhen AND 
                    d.id IS NULL AND c.id IS NULL 
                    LIMIT :chunk) RETURNING id, name, chain_id as chain"""
    )
    List<Map<String, String>> pruneByCreatedWhen(@NonNull Timestamp createdWhen, int chunk);

    List<Snapshot> findAllByChainId(String chainId);

    Optional<Snapshot> findFirstByChainOrderByIdDesc(Chain chain);

    void deleteAllByChainId(String chainId);

    @Query(nativeQuery = true,
            value = """
                    select 'V' || (max(val) + 1)
                    FROM (
                        select num as val
                        from (
                            select CAST(replace(name, 'V', '') AS INTEGER) num
                            from {h-schema}snapshots
                            where chain_id = :chainId
                              and name ~ 'V[0-9]+$'
                            order by CAST(replace(name, 'V', '')AS INTEGER) desc
                            LIMIT 1
                        ) t2
                        union all
                        select count(name) as val
                        from {h-schema}snapshots as t1
                        where chain_id = :chainId
                    ) t"""
    )
    String getNextAvailableName(String chainId);

    Optional<Snapshot> findSnapshotByChainAndName(Chain chain, String name);

    @Query(value = """
        SELECT s.*
        FROM (SELECT chain_id as chain_id, id as snapshot_id, row_number() over(partition by chain_id order by created_when desc) as rn
              FROM catalog.snapshots) AS rns LEFT JOIN catalog.snapshots s ON s.id = rns.snapshot_id
        WHERE rns.chain_id IN (:chainIds) AND rn = 1
""", nativeQuery = true)
    List<Snapshot> findAllLastCreated(Collection<String> chainIds);
}
