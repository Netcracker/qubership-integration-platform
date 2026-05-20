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

import jakarta.transaction.Transactional;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContextStorageRespository extends JpaRepository<ContextSystemRecords, String> {
    Optional<ContextSystemRecords> findByContextServiceIdAndContextId(String contextServiceId, String contextId);

    @Modifying
    @Transactional
    @Query(
            nativeQuery = true,
            value = "DELETE FROM engine.context_system_records record"
                    + " WHERE record.context_service_id = :contextServiceId AND record.context_Id = :contextId")
    void deleteRecordByContextServiceIdAndContextId(@Param("contextServiceId") String contextServiceId, @Param("contextId") String contextId);

    @Transactional
    List<ContextSystemRecords> findAllByExpiresAtBefore(Timestamp expiresAt);
}
