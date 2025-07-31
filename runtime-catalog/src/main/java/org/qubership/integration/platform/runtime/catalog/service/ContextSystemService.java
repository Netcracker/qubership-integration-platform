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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SystemDeleteException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context.ContextSystemRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemSearchRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.ContextSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.context.ContextSystemUpdateRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.ContextSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.filter.SystemFilterSpecificationBuilder;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ContextSystemService extends AbstractContextSystemService {

    private final SystemFilterSpecificationBuilder systemFilterSpecificationBuilder;
    private final ContextSystemMapper contextSystemMapper;
    private final ChainService chainService;

    public ContextSystemService(ContextSystemRepository contextSystemRepository,
                                ContextSystemMapper contextSystemMapper,
                                ActionsLogService actionLogger,
                                SystemFilterSpecificationBuilder systemFilterSpecificationBuilder, @Lazy ChainService chainService) {
        super(
                contextSystemRepository,
                actionLogger
        );
        this.systemFilterSpecificationBuilder = systemFilterSpecificationBuilder;
        this.contextSystemMapper = contextSystemMapper;
        this.chainService = chainService;
    }


    public ContextSystem create(ContextSystemRequestDTO requestedContextSystem) {
        requestedContextSystem.setId(UUID.randomUUID().toString());
        ContextSystem createdSystem = contextSystemMapper.toContextSystem(requestedContextSystem);
        return enrichAndSaveContextSystem(createdSystem, false);
    }

    public void deleteById(String contextId) {
        if (chainService.isContextUsedByChain(contextId)) {
            throw new SystemDeleteException("Service used by one or more chains");
        }
        ContextSystem contextSystem = findById(contextId);
        contextSystemRepository.delete(contextSystem);
        logContextSystemAction(contextSystem, LogOperation.DELETE);

    }

    public ContextSystem update(ContextSystemUpdateRequestDTO requestContextSystem, String systemId) {
        ContextSystem contextSystem = findById(systemId);
        contextSystem = contextSystemMapper.update(contextSystem, requestContextSystem);
        contextSystem = save(contextSystem);
        logContextSystemAction(contextSystem, LogOperation.UPDATE);
        return contextSystem;
    }

    public List<ContextSystem> searchContextSystem(SystemSearchRequestDTO systemSearchRequestDT) {
        return searchContextService(systemSearchRequestDT.getSearchCondition());
    }

    public List<ContextSystem> searchContextService(String searchString) {
        return contextSystemRepository.findByNameContaining(searchString);
    }


    @Transactional
    public List<ContextSystem> findByFilterRequest(List<FilterRequestDTO> filters) {
        Specification<ContextSystem> specification = systemFilterSpecificationBuilder.buildContextFilter(filters);

        return contextSystemRepository.findAll(specification);
    }
}
