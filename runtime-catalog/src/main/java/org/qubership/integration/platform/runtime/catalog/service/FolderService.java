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

package org.qubership.integration.platform.runtime.catalog.service;

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Folder;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.FolderRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedList;
import java.util.List;

@Service
@Transactional
public class FolderService {
    private final FolderRepository folderRepository;
    private final ActionsLogService actionLogger;

    private final AuditingHandler auditingHandler;

    @Autowired
    public FolderService(FolderRepository folderRepository,
                         ActionsLogService actionLogger,
                         AuditingHandler jpaAuditingHandler) {
        this.folderRepository = folderRepository;
        this.actionLogger = actionLogger;
        this.auditingHandler = jpaAuditingHandler;
    }

    public Folder findFirstByName(String name, Folder parent) {
        return folderRepository.findFirstByNameAndParentFolder(name, parent);
    }

    public Folder findEntityByIdOrNull(String folderId) {
        return folderId != null ? folderRepository.findById(folderId).orElse(null) : null;
    }

    public Folder save(Folder folder, String parentFolderId) {
        auditingHandler.markModified(folder);
        return upsertFolder(folder, parentFolderId);
    }

    public Folder save(Folder folder, Folder parentFolder) {
        if (parentFolder == null) {
            return save(folder, (String) null);
        }
        return save(folder, parentFolder.getId());
    }

    private Folder upsertFolder(Folder folder, String parentFolderId) {
        Folder newFolder = folderRepository.save(folder);
        if (parentFolderId != null) {
            Folder parentFolder = findEntityByIdOrNull(parentFolderId);
            parentFolder.addChildFolder(newFolder);
            newFolder = folderRepository.save(newFolder);
        }
        return newFolder;
    }

    public Folder setActualizedFolderState(Folder folderState) {
        List<Folder> actualizedFolderList = new LinkedList<>(folderState
                .getFolderList()
                .stream()
                .map(folderRepository::persist)
                .toList());

        folderState.setFolderList(actualizedFolderList);

        if (folderState.getParentFolder() != null) {
           Folder actualizedParentFolder = setActualizedFolderState(folderState.getParentFolder());
           folderState.setParentFolder(actualizedParentFolder);
        }

        return folderRepository.persist(folderState);
    }
}
