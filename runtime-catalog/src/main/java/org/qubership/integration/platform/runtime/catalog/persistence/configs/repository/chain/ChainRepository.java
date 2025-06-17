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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.sql.Timestamp;
import java.util.List;

public interface ChainRepository extends CommonRepository<Chain>, JpaRepository<Chain, String>, JpaSpecificationExecutor<Chain> {
    boolean existsByNameAndParentFolderId(String name, String parentFolderId);

    @Modifying
    @Query("update chains chain set chain.modifiedWhen = :modifiedWhen where chain.id = :chainId")
    void updateModificationTimestamp(String chainId, Timestamp modifiedWhen);

    @Modifying
    @Query("update chains chain set chain.currentSnapshot = :snapshot where chain.id = :chainId")
    void updateCurrentSnapshot(String chainId, Snapshot snapshot);

    @Modifying
    @Query("update chains chain set chain.unsavedChanges = :unsavedChanges where chain.id = :chainId")
    void updateUnsavedChanges(String chainId, boolean unsavedChanges);

    @Query(
            nativeQuery = true,
            value = """
                SELECT *
                FROM catalog.chains ch
                WHERE ch.parent_folder_id IN (
                    WITH RECURSIVE parent_folders AS (
                        SELECT f1.*
                        FROM catalog.folders f1
                        WHERE f1.id = :folderId

                        UNION ALL

                        SELECT f2.*
                        FROM catalog.folders f2
                                 INNER JOIN parent_folders pf ON f2.id = pf.parent_folder_id
                    )
                    SELECT DISTINCT id
                    FROM parent_folders)"""
    )
    List<Chain> findAllChainsToRootParentFolder(String folderId);

    @Query(
            nativeQuery = true,
            value = """
                SELECT *
                FROM catalog.chains ch
                WHERE ch.parent_folder_id IN (
                    WITH RECURSIVE parent_folders AS (
                        SELECT f1.*
                        FROM catalog.folders f1
                        WHERE f1.id in :folderIds

                        UNION ALL

                        SELECT f2.*
                        FROM catalog.folders f2
                                 INNER JOIN parent_folders pf ON f2.parent_folder_id = pf.id
                    )
                    SELECT DISTINCT id
                    FROM parent_folders)"""
    )
    List<Chain> findAllChainsInFolders(List<String> folderIds);

    @Query(
            nativeQuery = true,
            value = """
                select chain_id
                from catalog.elements
                where id in (select properties ->> 'elementId'
                             from catalog.elements
                             where chain_id in :chainsIds
                               and type IN ('chain-call', 'chain-call-2')
                             UNION
                             select properties -> 'chainFailureHandlerContainer' ->> 'elementId'
                             from catalog.elements
                             where chain_id in :chainsIds
                                and type IN ('http-trigger'))"""
    )
    List<String> findSubChains(List<String> chainsIds);

    @Query("SELECT id FROM chains")
    List<String> findAllId();

    @Query(
            nativeQuery = true,
            value = """
                WITH requested_chain AS (select coalesce(c.last_import_hash, '0') AS last_import_hash
                                         FROM catalog.chains c
                                         WHERE c.id = :chainId)
                SELECT rc.last_import_hash
                FROM requested_chain rc
                UNION ALL
                SELECT '0'
                WHERE NOT EXISTS(SELECT NULL FROM requested_chain)
            """
    )
    String getChainLastImportHash(String chainId);
}
