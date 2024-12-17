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

import org.qubership.integration.platform.engine.persistence.shared.entity.Checkpoint;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CheckpointRepository extends JpaRepository<Checkpoint, String> {

    Checkpoint findFirstBySessionIdAndSessionChainIdAndCheckpointElementId(
        String sessionId, String chainId, String checkpointElementId);

    List<Checkpoint> findAllBySessionChainIdAndSessionId(String chainId, String sessionId,
        Pageable pageable);

    @Query(nativeQuery = true, value = """
                SELECT si.chain_id AS chain_id,
                       si.chain_name AS chain_name,
                       SUM( octet_length(chpt.id)
                           + octet_length(chpt.session_id)
                           + octet_length(chpt.checkpoint_element_id)
                           + octet_length(chpt.headers)
                           + 4                         --oid fixed size
                           + length(lo_get(chpt.body)) --actual body size from pg_large_objects
                           + 8                         --timestamp fixed size
                           + octet_length(chpt.context_data) ) AS raw_data_size
                FROM engine.checkpoints chpt LEFT JOIN engine.sessions_info si ON chpt.session_id = si.id
                GROUP BY si.chain_id, si.chain_name;
            """)
    List<Object[]> findAllChainCheckpointSize();
}
