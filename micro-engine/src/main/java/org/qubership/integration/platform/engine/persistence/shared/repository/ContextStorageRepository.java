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
import jakarta.transaction.Transactional;
import org.qubership.integration.platform.engine.persistence.shared.entity.ContextSystemRecords;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ContextStorageRepository implements PanacheRepositoryBase<ContextSystemRecords, String> {
    public Optional<ContextSystemRecords> findByContextServiceIdAndContextId(
            String contextServiceId,
            String contextId
    ) {
        return find("contextServiceId = ?1 and contextId = ?2", contextServiceId, contextId)
                .firstResultOptional();
    }

    @Transactional
    public void deleteRecordByContextServiceIdAndContextId(
            String contextServiceId,
            String contextId
    ) {
        delete("contextServiceId = ?1 and contextId = ?2", contextServiceId, contextId);
    }

    public @Transactional
    List<ContextSystemRecords> findAllByExpiresAtBefore(Timestamp expiresAt) {
        return list("expiresAt < ?1", expiresAt);
    }

    public void deleteByIds(List<String> ids) {
        delete("id in (?1)", ids);
    }
}
