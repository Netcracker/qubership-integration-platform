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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementFilterRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ElementValidationRepository extends CommonRepository<ChainElement>, JpaRepository<ChainElement, String>, JpaSpecificationExecutor<ChainElement>, ElementFilterRepository {
    @Query(value = """
            SELECT *
            FROM catalog.elements e1
            WHERE e1.type IN ('chain-call', 'chain-call-2')
              AND e1.chain_id IS NOT NULL
              AND e1.properties ->> 'elementId' NOT IN (SELECT e2.properties ->> 'elementId' as chain_trigger_id
                                                    FROM catalog.elements e2
                                                    WHERE e2.type IN ('chain-trigger', 'chain-trigger-2')
                                                      AND e2.chain_id IS NOT NULL)""", nativeQuery = true)
    List<ChainElement> findAllForChainRefNoSubChainValidation();

    @Query(value = """
            SELECT *
            FROM catalog.elements
            WHERE chain_id IS NOT NULL
              AND ((type = 'script')
                OR (type = 'http-trigger' AND properties ->> 'handlerContainer' IS NOT NULL AND properties #> '{handlerContainer,script}' IS NOT NULL)
                OR (type = 'service-call'
                    AND (
                        (properties ->> 'before' IS NOT NULL AND properties #>> '{before,type}' = 'script')
                        OR (properties ->> 'handlerContainer' IS NOT NULL AND properties #> '{handlerContainer,script}' IS NOT NULL)
                        OR (properties ->> 'after' IS NOT NULL AND EXISTS (
                            SELECT *
                            FROM jsonb_array_elements(properties -> 'after') t2
                            WHERE t2 -> 'type' IS NOT NULL AND t2 ->> 'type' = 'script'
                            ))
                        )
                    )
                )""", nativeQuery = true)
    List<ChainElement> findAllForExcessiveScriptUsageValidation();

    @Query(value = """
            SELECT *
            FROM catalog.elements
            WHERE chain_id IS NOT NULL
                AND type = 'http-trigger'
                AND properties ->> 'accessControlType' = 'RBAC'
                AND (properties -> 'roles' IS NULL OR jsonb_array_length(properties -> 'roles') = 0)""", nativeQuery = true)
    List<ChainElement> findAllForLowChainSecurityValidation();

    @Query(value = """
            SELECT el1.*
            FROM catalog.elements el1
                     LEFT JOIN (SELECT el2.properties ->> 'elementId' as trigger_ref_id, COUNT(el2.id) as count
                                FROM catalog.elements el2
                                WHERE el2.chain_id IS NOT NULL
                                  AND el2.type IN ('chain-call', 'chain-call-2')
                                GROUP BY trigger_ref_id) sub ON el1.properties ->> 'elementId' = sub.trigger_ref_id
            WHERE el1.chain_id IS NOT NULL
              AND el1.type IN ('chain-trigger', 'chain-trigger-2')
              AND coalesce(sub.count, 0) < :minUsageCount""", nativeQuery = true)
    List<ChainElement> findAllForSubChainNotUsedValidation(Integer minUsageCount);

    @Query(value = """
            SELECT el.*
            FROM catalog.elements el
                JOIN catalog.integration_system sys ON el.properties ->> 'integrationSystemId' = sys.id
                JOIN catalog.environment env ON sys.active_environment_id = env.id
            WHERE el.chain_id IS NOT NULL
                AND (
                    (el.type IN ('http-trigger', 'http-sender', 'scs-sender')
                         AND (el.properties -> 'connectTimeout' IS NULL OR el.properties ->> 'connectTimeout' = ''))
                    OR (el.type = 'mail-sender' AND (el.properties -> 'connectionTimeout' IS NULL OR el.properties ->> 'connectionTimeout' = ''))
                    OR (el.type = 'sds-trigger' AND (el.properties -> 'parallelRunTimeout' IS NULL OR el.properties ->> 'parallelRunTimeout' = ''))
                    OR (el.type = 'service-call'
                            AND el.properties ->> 'integrationOperationProtocolType' = 'http'
                            AND (
                                ((el.properties #> '{integrationAdditionalParameters,soTimeout}' IS NULL
                                     OR el.properties #>> '{integrationAdditionalParameters,soTimeout}' = '')
                                    AND (env IS NULL OR env.properties -> 'soTimeout' IS NULL OR env.properties ->> 'soTimeout' = ''))
                                OR ((el.properties #> '{integrationAdditionalParameters,connectTimeout}' IS NULL
                                    OR el.properties #>> '{integrationAdditionalParameters,connectTimeout}' = '')
                                    AND (env IS NULL OR env.properties -> 'connectTimeout' IS NULL OR env.properties ->> 'connectTimeout' = ''))
                                OR ((el.properties #> '{integrationAdditionalParameters,connectionRequestTimeout}' IS NULL
                                    OR el.properties #>> '{integrationAdditionalParameters,connectionRequestTimeout}' = '')
                                    AND (env IS NULL OR env.properties -> 'connectionRequestTimeout' IS NULL OR env.properties ->> 'connectionRequestTimeout' = ''))
                                OR ((el.properties #> '{integrationAdditionalParameters,responseTimeout}' IS NULL
                                    OR el.properties #>> '{integrationAdditionalParameters,responseTimeout}' = '')
                                    AND (env IS NULL OR env.properties -> 'responseTimeout' IS NULL OR env.properties ->> 'responseTimeout' = ''))
                                )
                    )
                )""", nativeQuery = true)
    List<ChainElement> findAllForElementTimeoutIsEmptyValidation();

}
