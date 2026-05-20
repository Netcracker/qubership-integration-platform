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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common.CommonRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FolderRepository extends CommonRepository<Folder>, JpaRepository<Folder, String> {
    Folder findByName(String name);

    Folder findFirstByName(String name);

    Folder findFirstByNameAndParentFolder(String name, Folder parentFolder);

    List<Folder> findByNameContaining(String name);

    List<Folder> findAllByParentFolderIsNull();

    List<Folder> findAllByParentFolderEquals(Folder folder);

    @Query(
            nativeQuery = true,
            value = """
                WITH RECURSIVE folder_hierarchy AS (
                        SELECT
                            f1.*
                        FROM
                            catalog.folders f1
                        WHERE f1.id = :folderId
                        UNION ALL
                        SELECT
                            f2.*
                        FROM catalog.folders f2 INNER JOIN folder_hierarchy fh
                            ON f2.id = fh.parent_folder_id
                )
                SELECT * FROM folder_hierarchy;
            """
    )
    List<Folder> getPath(String folderId);

    @Query(
            nativeQuery = true,
            value = """
                        WITH RECURSIVE folder_hierarchy AS (
                                SELECT
                                    f1.*
                                FROM
                                    catalog.folders f1
                                WHERE f1.name = :folderName
                                UNION ALL
                                SELECT
                                    f2.*
                                FROM catalog.folders f2 INNER JOIN folder_hierarchy fh
                                    ON f2.id = fh.parent_folder_id
                        )
                        SELECT * FROM folder_hierarchy;
                    """
    )
    List<Folder> getPathByName(String folderName);

    @Query(
            nativeQuery = true,
            value = """
                    WITH RECURSIVE folder_hierarchy AS (
                            SELECT
                                f1.*
                            FROM
                                catalog.folders f1
                            WHERE f1.id in :chainFolderIds
                            UNION ALL
                            SELECT
                                f2.*
                            FROM catalog.folders f2 INNER JOIN folder_hierarchy fh
                                ON f2.id = fh.parent_folder_id)
                    SELECT DISTINCT * FROM folder_hierarchy;"""
    )
    List<Folder> getFoldersHierarchically(List<String> chainFolderIds);

    @Query(
            nativeQuery = true,
            value = """
                    with recursive nested_folders as (
                        select f1.* from catalog.folders f1
                               where f1.parent_folder_id = :folderId
                        union all
                        select f2.* from catalog.folders f2
                               inner join nested_folders nf
                                       on f2.parent_folder_id = nf.id
                    ) select distinct * from nested_folders"""
    )
    List<Folder> findNestedFolders(String folderId);

    @Query(
            nativeQuery = true,
            value = """
                    WITH parent_folders_table AS (
                        WITH RECURSIVE parent_folders AS (
                            SELECT f1.*
                            FROM catalog.folders f1
                            WHERE f1.id = :folderId OR f1.parent_folder_id = :folderId

                            UNION ALL

                            SELECT f2.*
                            FROM catalog.folders f2
                                     INNER JOIN parent_folders pf ON f2.id = pf.parent_folder_id
                        )
                        SELECT DISTINCT *
                        FROM parent_folders
                    )

                    SELECT *
                    FROM catalog.folders result
                    WHERE result.id IN (
                            SELECT id
                            FROM parent_folders_table)
                        OR result.parent_folder_id IN (
                            SELECT id
                            FROM parent_folders_table
                            WHERE parent_folder_id <> :folderId OR parent_folder_id IS NULL)"""
    )
    List<Folder> findAllFoldersToRootParentFolder(String folderId);

    @Query(
            nativeQuery = true,
            value = """
                with recursive nested_folders as (
                    select f1.* from catalog.folders f1
                           where f1.id in :folderIds
                    union all
                    select f2.* from catalog.folders f2
                           inner join nested_folders nf
                                   on f2.parent_folder_id = nf.id
                )
                delete
                from catalog.folders f
                where f.id in (select id from nested_folders)
                returning f.id
            """
    )
    List<String> deleteFolderTree(List<String> folderIds);
}
