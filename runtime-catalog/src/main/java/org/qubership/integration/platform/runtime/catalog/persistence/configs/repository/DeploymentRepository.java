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

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.util.SQLUtils;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, String> {

    List<Deployment> findAllByChainId(String chainId);

    List<Deployment> findAllByDomain(String domain);

    void deleteAllByChainId(String chainId);

    void deleteAllBySnapshotId(String snapshotId);

    long countByDomain(String domain);

    /**
     * Find deployments that need to be deployed. Deployments from the 'excludeIds' list
     * and earlier (by created_when, within the chain) are excluded
     */
    @Query(value = """
           SELECT d.*
           FROM catalog.deployments d
                    LEFT JOIN (SELECT ex_sub.ex_id,
                                      ex_sub.ex_chain_id,
                                      ex_sub.ex_domain,
                                      ex_sub.ex_created_when,
                                      ex_sub.created_rank
                               FROM (SELECT ex.id           as                                                   ex_id,
                                            ex.chain_id     as                                                   ex_chain_id,
                                            ex.domain       as                                                   ex_domain,
                                            ex.created_when as                                                   ex_created_when,
                                            RANK() OVER (PARTITION BY ex.chain_id ORDER BY ex.created_when DESC) created_rank
                                     FROM catalog.deployments ex
                                     WHERE ex.id IN :excludeIds) as ex_sub
                               WHERE ex_sub.created_rank = 1) AS ex_t
                              ON ex_t.ex_chain_id = d.chain_id AND ex_t.ex_domain = d.domain
           WHERE domain = :domainName
             AND id NOT IN :excludeIds
             AND (created_when > ex_created_when OR ex_id IS NULL)""",
            nativeQuery = true)
    List<Deployment> findDeploymentsToUpdate(String domainName, List<String> excludeIds);

    default Set<String> findDeploymentsToRemove(String domainName, List<String> excludeIds) {
        return findDeploymentsToRemove(domainName, SQLUtils.convertListToValuesQuery(excludeIds));
    }

    /**
     * @param domainName - engine domain
     * @param excludeIds - array in format "{abc,cde}"
     * @return deployments ids
     */
    @Query(value = """
            SELECT ex.id
            FROM (SELECT unnest(cast(:excludeIds AS TEXT ARRAY))) AS ex (id)
            WHERE ex.id NOT IN (SELECT d.id FROM catalog.deployments d WHERE d.domain = :domainName)""",
            nativeQuery = true)
    Set<String> findDeploymentsToRemove(String domainName, String excludeIds);

    @Modifying
    @Query(value = """
            DELETE FROM catalog.deployments d1
            WHERE d1.id IN (
                SELECT DISTINCT d.id
                FROM catalog.deployments d JOIN (SELECT *
                                                 FROM catalog.deployments d
                                                 WHERE d.id IN :deployed OR d.id IN :notDeployed) a ON d.chain_id = a.chain_id
                WHERE d.id NOT IN :deployed AND d.created_when < a.created_when)
            """,
            nativeQuery = true)
    void deleteObsoleteDeployments(Collection<String> deployed, Collection<String> notDeployed);
}
