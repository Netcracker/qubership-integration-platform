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

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.ChainBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.OVERRIDDEN_LABEL_NAME;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.OVERRIDES_LABEL_NAME;

@Slf4j
@Service
public class ChainService extends ChainBaseService {
    private static final String CHAIN_WITH_ID_NOT_FOUND_MESSAGE = "Can't find chain with id: ";

    private final ChainRepository chainRepository;
    private final DeploymentService deploymentService;
    private final ActionsLogService actionLogger;

    @Autowired
    public ChainService(
            ChainRepository chainRepository,
            ElementService elementService,
            @Lazy DeploymentService deploymentService,
            ActionsLogService actionLogger
    ) {
        super(chainRepository, elementService);
        this.chainRepository = chainRepository;
        this.deploymentService = deploymentService;
        this.actionLogger = actionLogger;
    }

    public Boolean exists(String chainId) {
        return chainRepository.existsById(chainId);
    }

    public List<Chain> findAll() {
        return chainRepository.findAll();
    }

    public List<Chain> findAllById(List<String> chainIds) {
        return chainRepository.findAllById(chainIds);
    }

    public Chain findById(String chainId) {
        return chainRepository.findById(chainId)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_WITH_ID_NOT_FOUND_MESSAGE + chainId));
    }

    public List<String> getSubChainsIds(List<String> chainsIds, List<String> resultChainsIds) {
        resultChainsIds.addAll(chainsIds);
        List<String> subChainsIds = chainRepository.findSubChains(chainsIds);
        subChainsIds.removeAll(resultChainsIds);
        if (!subChainsIds.isEmpty()) {
            getSubChainsIds(subChainsIds, resultChainsIds);
        }

        return resultChainsIds;
    }

    public Optional<Chain> tryFindById(String chainId) {
        return chainRepository.findById(chainId);
    }

    public boolean setOverriddenById(String chainId, String overriddenById) {
        Optional<Chain> optionalChain = tryFindById(chainId);
        if (optionalChain.isPresent()) {
            Chain chain = optionalChain.get();
            chain.setOverriddenByChainId(overriddenById);

            if (chain.getLabels().stream().noneMatch(label -> OVERRIDDEN_LABEL_NAME.equals(label.getName()))) {
                chain.addLabel(ChainLabel.builder()
                        .name(OVERRIDDEN_LABEL_NAME)
                        .technical(true)
                        .chain(chain)
                        .build());
            }
            chainRepository.save(chain);
            return true;
        }
        return false;
    }

    public boolean setOverridesChainId(String chainId, String overriddenById) {
        Optional<Chain> optionalChain = tryFindById(overriddenById);
        if (optionalChain.isPresent()) {
            Chain chain = optionalChain.get();
            chain.setOverridesChainId(chainId);

            if (chain.getLabels().stream().noneMatch(label -> OVERRIDES_LABEL_NAME.equals(label.getName()))) {
                chain.addLabel(ChainLabel.builder()
                        .name(OVERRIDES_LABEL_NAME)
                        .technical(true)
                        .chain(chain)
                        .build());
            }
            chainRepository.save(chain);
            return true;
        }
        return false;
    }

    public void clearCurrentSnapshot(String chainId) {
        chainRepository.updateCurrentSnapshot(chainId, null);
        chainRepository.updateUnsavedChanges(chainId, true);
    }

    public void setCurrentSnapshot(String chainId, Snapshot snapshot) {
        chainRepository.updateCurrentSnapshot(chainId, snapshot);
        chainRepository.updateUnsavedChanges(chainId, false);
    }

    public void overrideModificationTimestamp(Chain chain, Timestamp timestamp) {
        chainRepository.updateModificationTimestamp(chain.getId(), timestamp);
    }

    public void setActualizedChainState(Chain currentChainState, Chain newChainState) {
        chainRepository.actualizeObjectState(currentChainState, newChainState);
    }

    public void update(Chain chain) {
        chainRepository.save(chain);
        logChainAction(chain, LogOperation.UPDATE);
    }

    public String getChainHash(String chainId) {
        return chainRepository.getChainLastImportHash(chainId);
    }

    public void clearContext() {
        chainRepository.clearContext();
    }

    public Optional<Chain> deleteByIdIfExists(String chainId) {
        deploymentService.deleteAllByChainId(chainId);
        Optional<Chain> optionalChain = tryFindById(chainId);
        if (optionalChain.isPresent()) {
            Chain chain = optionalChain.get();
            chainRepository.deleteById(chainId);

            logChainAction(chain, LogOperation.DELETE);
        }

        return optionalChain;
    }

    private void logChainAction(Chain chain, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CHAIN)
                .entityId(chain.getId())
                .entityName(chain.getName())
                .parentType(chain.getParentFolder() == null ? null : EntityType.FOLDER)
                .parentId(chain.getParentFolder() == null ? null : chain.getParentFolder().getId())
                .parentName(chain.getParentFolder() == null ? null : chain.getParentFolder().getName())
                .operation(operation)
                .build());
    }
}
