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

package org.qubership.integration.platform.engine.persistence.configs.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.engine.persistence.configs.entity.SdsJobLock;

import java.sql.Timestamp;
import java.util.Collection;

@ApplicationScoped
public class SdsJobLockRepository implements PanacheRepositoryBase<SdsJobLock, String> {
    public SdsJobLock findByJobId(String jobId) {
        return find("jobId", jobId).firstResult();
    }

    public boolean existsByJobId(String jobId) {
        return count("jobId", jobId) > 0;
    }

    public void deleteAllByJobIdAndCreatedLessThanEqual(String jobId, Timestamp olderThan) {
        delete("jobId = ?1 and created <= ?2", jobId, olderThan);
    }

    public void deleteAllByJobId(String jobId) {
        delete("jobId", jobId);
    }

    void deleteAllByExecutionId(String executionId) {
        delete("executionId", executionId);
    }

    void deleteAllByJobIdIn(Collection<String> jobIds) {
        delete("jobId in (?1)", jobIds);
    }
}
