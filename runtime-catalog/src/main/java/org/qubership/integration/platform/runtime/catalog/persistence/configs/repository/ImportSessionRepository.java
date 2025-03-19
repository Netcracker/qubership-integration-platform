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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ImportSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportSessionRepository extends JpaRepository<ImportSession, String> {

    @Modifying
    @Query(
            nativeQuery = true,
            value = "DELETE FROM catalog.import_sessions "
                    + "WHERE modified_when <= NOW() - make_interval(0, 0, 0, 0, 0, :sessionLifetimeMinutes, 0)"
    )
    void deleteSessionsOlderThan(int sessionLifetimeMinutes);
}
