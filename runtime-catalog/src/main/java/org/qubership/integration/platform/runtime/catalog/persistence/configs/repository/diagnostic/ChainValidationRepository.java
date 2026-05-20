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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChainValidationRepository extends CommonRepository<Chain>, JpaRepository<Chain, String>, JpaSpecificationExecutor<Chain> {
    @Query(value = """
            SELECT s.chain_id AS chain_id, COUNT(s.id) AS snapshots_count
            FROM catalog.chains c JOIN catalog.snapshots s ON c.id = s.chain_id
            WHERE s.created_when < (NOW() - ( :olderThan )\\:\\:INTERVAL)
            GROUP BY chain_id
            HAVING COUNT(s.id) >= :snapshotsCount""", nativeQuery = true)
    List<String[]> findAllForLargeSnapshotsNumberValidation(String olderThan, Integer snapshotsCount);
}
