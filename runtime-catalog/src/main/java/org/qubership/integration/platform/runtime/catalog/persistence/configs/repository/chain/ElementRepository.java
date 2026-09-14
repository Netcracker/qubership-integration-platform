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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain;

import jakarta.annotation.Nullable;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.NotEmpty;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.SwimlaneChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ElementRepository extends CommonRepository<ChainElement>, JpaRepository<ChainElement, String>, JpaSpecificationExecutor<ChainElement>, ElementFilterRepository {

    List<ChainElement> findAllByTypeInAndChainNotNull(Collection<String> type);

    @Query(value = "select e from elements e join fetch e.chain where e.type in ?1 and e.chain is not null")
    List<ChainElement> findAllByTypeInAndFetchChain(Collection<String> type);

    Optional<ChainElement> findByOriginalId(String originalId);

    ChainElement findByIdAndChainId(String id, String chainId);

    List<ChainElement> findAllByChainId(String chainId);

    List<ChainElement> findAllBySnapshotIdAndType(String snapshotId, String type);

    List<ChainElement> findAllBySnapshotIdAndTypeIn(String snapshotId, List<String> type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM swimlane_elements e WHERE e.id = :id")
    Optional<SwimlaneChainElement> findSwimlaneWithLockingById(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM swimlane_elements e WHERE e.id = :id AND e.chain.id = :chainId")
    Optional<SwimlaneChainElement> findSwimlaneWithLockingByIdAndChainId(String id, String chainId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM swimlane_elements e WHERE e.chain.id = :chainId AND e.defaultSwimlane = TRUE")
    Optional<SwimlaneChainElement> findDefaultSwimlaneWithLockingByChainId(String chainId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM swimlane_elements e WHERE e.chain.id = :chainId AND e.reuseSwimlane = TRUE")
    Optional<SwimlaneChainElement> findReuseSwimlaneWithLockingByChainId(String chainId);

    @Query("""
        SELECT e
        FROM elements e
        WHERE e.type IN :types
            AND (e.snapshot.id IN (
                    SELECT d.snapshot.id
                    FROM deployments d
                    WHERE
                        d.domain <> :domain
                        AND d.chain.id = :excludeChainId
                        AND (:excludeDeploymentIds IS NULL
                        OR d.id NOT IN :excludeDeploymentIds)
                    )
                )""")
    List<ChainElement> findElementsForDomainTriggerCheck(
            List<String> types,
            String domain,
            String excludeChainId,
            @Nullable @NotEmpty List<String> excludeDeploymentIds);

    @Query("""
        SELECT e
        FROM elements e
        WHERE e.type IN :types
            AND (e.snapshot.id IN (
                    SELECT d.snapshot.id
                    FROM deployments d
                    WHERE
                        d.chain.id <> :excludeChainId
                        AND (:excludeDeploymentIds IS NULL
                        OR d.id NOT IN :excludeDeploymentIds)
                    )
                )""")
    List<ChainElement> findElementsForTriggerCheck(
            List<String> types,
            String excludeChainId,
            @Nullable @NotEmpty List<String> excludeDeploymentIds);

    @Query("""
        SELECT e
        FROM elements e
            INNER JOIN deployments d ON e.snapshot.id = d.snapshot.id
        WHERE
            e.type IN :types
            AND d.id IN :deploymentIds""")
    Collection<ChainElement> findElementsByTypesAndDeployments(
            Collection<String> types,
            Collection<String> deploymentIds
    );

    @Query("SELECT e "
            + "FROM elements e "
            + "INNER JOIN deployments d "
            + "ON e.snapshot.id = d.snapshot.id "
            + "WHERE "
            + "e.type IN :types "
            + "AND d.chain.id = :chainId "
            + "AND d.id <> :excludeDeploymentId")
    List<ChainElement> findDeployedElementsByTypesAndChainId(
            Collection<String> types,
            String chainId,
            String excludeDeploymentId
    );

    @Query("SELECT e FROM elements e WHERE e.type IN :types AND e.snapshot.id IN :snapshotIds")
    Collection<ChainElement> findElementsByTypesAndSnapshots(
            Collection<String> types,
            Collection<String> snapshotIds
    );

    @Query("SELECT e FROM elements e WHERE e.type IN :types AND e.chain.id IN :chainIds")
    Collection<ChainElement> findElementsByTypesAndChains(
            Collection<String> types,
            Collection<String> chainIds
    );

    @Query("SELECT e FROM elements e INNER JOIN deployments d ON e.snapshot.id = d.snapshot.id WHERE e.type IN :types")
    Collection<ChainElement> findAllDeployedElementsByTypes(Collection<String> types);

    @Query("SELECT e "
            + "FROM elements e "
            + "INNER JOIN deployments d "
            + "ON e.snapshot.id = d.snapshot.id "
            + "WHERE e.type IN :types AND d.chain.id <> :excludeChainId")
    List<ChainElement> findElementsForRouteExistenceCheck(
            List<String> types,
            String excludeChainId);

    List<ChainElement> findAllByParentId(String parentId);

    List<ChainElement> findAllBySnapshotId(String id);

    @Query("SELECT e FROM elements e WHERE e.chain.id = :chainId AND e.type IN :types")
    List<ChainElement> findAllByChainIdAndTypeIn(String chainId, @Nullable @NotEmpty Collection<String> types);

    void deleteAllByChainId(String chainId);

    @Query("SELECT e.type FROM elements e GROUP BY e.type")
    List<String> findAllGroupByType();
}
