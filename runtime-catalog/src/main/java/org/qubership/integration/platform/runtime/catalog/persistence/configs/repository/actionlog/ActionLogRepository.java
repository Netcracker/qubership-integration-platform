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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.actionlog;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.sql.Timestamp;
import java.util.List;

public interface ActionLogRepository extends
        PagingAndSortingRepository<ActionLog, String>,
        ActionLogFilterRepository,
        CrudRepository<ActionLog, String> {
    /**
     * Remove old records for scheduled cleanup task
     *
     * @param olderThan interval string, for example: '1 hour', '7 days', '2 years 3 month'
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value = "DELETE FROM catalog.logged_actions act "
                        + "WHERE act.action_time < now() - ( :olderThan )\\:\\:interval"
    )
    void deleteAllOldRecordsByInterval(String olderThan);

    List<ActionLog> findAllByActionTimeBetween(Timestamp actionTimeFrom, Timestamp actionTimeTo);
}
