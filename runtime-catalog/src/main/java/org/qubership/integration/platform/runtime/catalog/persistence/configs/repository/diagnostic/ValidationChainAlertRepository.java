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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ValidationChainAlertRepository extends CommonRepository<ValidationChainAlert>, JpaRepository<ValidationChainAlert, String>, JpaSpecificationExecutor<ValidationChainAlert> {
    List<ValidationChainAlert> findAllByValidationId(String validationId);

    long countAllByValidationId(String validationId);

    @Query(value = """
            SELECT DISTINCT count(*) OVER () as count
            FROM catalog.validation_chains_alerts
            WHERE validation_id = :validationId
            GROUP BY chain_id""", nativeQuery = true)
    Long countAllByValidationIdGroupByChain(String validationId);

    @Modifying
    void deleteAllByValidationId(String validationId);
}
