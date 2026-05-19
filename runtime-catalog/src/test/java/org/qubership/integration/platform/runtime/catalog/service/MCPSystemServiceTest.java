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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SystemDeleteException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystemLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.mcp.MCPSystemRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.MCPSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.filter.MCPSystemFilterSpecificationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ContextConfiguration(classes = MCPSystemService.class)
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class MCPSystemServiceTest {

    private static final String SYSTEM_ID = "system-id-1";
    private static final String SYSTEM_NAME = "Test MCP System";
    private static final String MCP_SERVICE_IDS_PROPERTY = "mcpServiceIds";

    @MockitoBean
    MCPSystemRepository mcpSystemRepository;

    @MockitoBean
    MCPSystemMapper mcpSystemMapper;

    @MockitoBean
    ActionsLogService actionLogger;

    @MockitoBean
    MCPSystemFilterSpecificationBuilder mcpSystemFilterSpecificationBuilder;

    @MockitoBean
    ChainRepository chainRepository;

    @Captor
    ArgumentCaptor<ActionLog> actionLogCaptor;

    @Autowired
    MCPSystemService mcpSystemService;

    private MCPSystem mcpSystem;

    @BeforeEach
    void setUp() {
        mcpSystem = MCPSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .build();
    }

    // findAll tests

    @Test
    @DisplayName("findAll without chains returns repository result")
    void findAllWithoutChainsReturnsRepositoryResult() {
        List<MCPSystem> systems = List.of(mcpSystem);
        when(mcpSystemRepository.findAll()).thenReturn(systems);

        List<MCPSystem> result = mcpSystemService.findAll(false);

        assertThat(result, equalTo(systems));
        verifyNoInteractions(chainRepository);
    }

    @Test
    @DisplayName("findAll with chains enriches each system with chain list")
    void findAllWithChainsEnrichesWithChains() {
        List<MCPSystem> systems = List.of(mcpSystem);
        List<Chain> chains = List.of(Chain.builder().id("chain-1").build());
        when(mcpSystemRepository.findAll()).thenReturn(systems);
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(chains);

        List<MCPSystem> result = mcpSystemService.findAll(true);

        assertThat(result.get(0).getChains(), equalTo(chains));
    }

    @Test
    @DisplayName("findAll default overload delegates to findAll(false)")
    void findAllDefaultDelegatesToFindAllWithoutChains() {
        when(mcpSystemRepository.findAll()).thenReturn(List.of());

        mcpSystemService.findAll();

        verify(mcpSystemRepository).findAll();
        verifyNoInteractions(chainRepository);
    }

    // findAllById tests

    @Test
    @DisplayName("findAllById delegates to repository")
    void findAllByIdDelegatesToRepository() {
        List<String> ids = List.of(SYSTEM_ID);
        List<MCPSystem> systems = List.of(mcpSystem);
        when(mcpSystemRepository.findAllById(ids)).thenReturn(systems);

        List<MCPSystem> result = mcpSystemService.findAllById(ids);

        assertThat(result, equalTo(systems));
    }

    // findById tests

    @Test
    @DisplayName("findById returns optional from repository")
    void findByIdReturnsOptionalFromRepository() {
        when(mcpSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.of(mcpSystem));

        Optional<MCPSystem> result = mcpSystemService.findById(SYSTEM_ID);

        assertTrue(result.isPresent());
        assertThat(result.get(), equalTo(mcpSystem));
    }

    @Test
    @DisplayName("findById returns empty optional when not found")
    void findByIdReturnsEmptyWhenNotFound() {
        when(mcpSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.empty());

        Optional<MCPSystem> result = mcpSystemService.findById(SYSTEM_ID);

        assertFalse(result.isPresent());
    }

    // create(MCPSystemRequestDTO) tests

    @Test
    @DisplayName("create from request saves system with mapped fields")
    void createFromRequestSavesSystemWithMappedFields() {
        MCPSystemRequestDTO request = new MCPSystemRequestDTO();
        request.setName("name");
        request.setDescription("desc");
        request.setIdentifier("ident");
        request.setInstructions("instruc");

        when(mcpSystemRepository.save(any(MCPSystem.class))).thenAnswer(inv -> inv.getArgument(0));

        MCPSystem result = mcpSystemService.create(request);

        assertThat(result.getName(), equalTo("name"));
        assertThat(result.getDescription(), equalTo("desc"));
        assertThat(result.getIdentifier(), equalTo("ident"));
        assertThat(result.getInstructions(), equalTo("instruc"));
    }

    // create(MCPSystem, boolean) tests

    @Test
    @DisplayName("create with isImport=false logs CREATE operation")
    void createWithIsImportFalseLogsCreate() {
        when(mcpSystemRepository.save(mcpSystem)).thenReturn(mcpSystem);

        mcpSystemService.create(mcpSystem, false);

        verify(actionLogger).logAction(actionLogCaptor.capture());
        ActionLog log = actionLogCaptor.getValue();
        assertThat(log.getOperation(), equalTo(LogOperation.CREATE));
        assertThat(log.getEntityType(), equalTo(EntityType.MCP_SYSTEM));
        assertThat(log.getEntityId(), equalTo(SYSTEM_ID));
        assertThat(log.getEntityName(), equalTo(SYSTEM_NAME));
    }

    @Test
    @DisplayName("create with isImport=true logs IMPORT operation")
    void createWithIsImportTrueLogsImport() {
        when(mcpSystemRepository.save(mcpSystem)).thenReturn(mcpSystem);

        mcpSystemService.create(mcpSystem, true);

        verify(actionLogger).logAction(actionLogCaptor.capture());
        assertThat(actionLogCaptor.getValue().getOperation(), equalTo(LogOperation.IMPORT));
    }

    // update(String, MCPSystemRequestDTO) tests

    @Test
    @DisplayName("update throws EntityNotFoundException when system not found")
    void updateThrowsEntityNotFoundWhenSystemNotFound() {
        when(mcpSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.empty());

        MCPSystemRequestDTO request = new MCPSystemRequestDTO();
        request.setLabels(List.of());

        assertThrows(EntityNotFoundException.class, () -> mcpSystemService.update(SYSTEM_ID, request));
    }

    @Test
    @DisplayName("update saves system and logs UPDATE operation")
    void updateSavesAndLogsUpdate() {
        MCPSystemRequestDTO request = new MCPSystemRequestDTO();
        request.setLabels(List.of());

        when(mcpSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.of(mcpSystem));
        when(mcpSystemMapper.updateWithoutLabels(eq(mcpSystem), eq(request))).thenReturn(mcpSystem);
        when(mcpSystemRepository.save(mcpSystem)).thenReturn(mcpSystem);

        MCPSystem result = mcpSystemService.update(SYSTEM_ID, request);

        assertThat(result, equalTo(mcpSystem));
        verify(actionLogger).logAction(actionLogCaptor.capture());
        assertThat(actionLogCaptor.getValue().getOperation(), equalTo(LogOperation.UPDATE));
    }

    // update(MCPSystem) tests

    @Test
    @DisplayName("update(MCPSystem) saves system and logs UPDATE")
    void updateMcpSystemSavesAndLogsUpdate() {
        when(mcpSystemRepository.save(mcpSystem)).thenReturn(mcpSystem);

        MCPSystem result = mcpSystemService.update(mcpSystem);

        assertThat(result, equalTo(mcpSystem));
        verify(actionLogger).logAction(actionLogCaptor.capture());
        assertThat(actionLogCaptor.getValue().getOperation(), equalTo(LogOperation.UPDATE));
    }

    // updateLabels tests

    @Test
    @DisplayName("updateLabels adds new labels not present in current set")
    void updateLabelsAddsNewLabels() {
        Set<MCPSystemLabel> currentLabels = new LinkedHashSet<>();
        MCPSystemLabel newLabel = new MCPSystemLabel("new-label", false, mcpSystem);
        List<MCPSystemLabel> newLabels = List.of(newLabel);

        mcpSystemService.updateLabels(mcpSystem, currentLabels, newLabels);

        assertThat(currentLabels, hasSize(1));
        assertThat(currentLabels.iterator().next().getName(), equalTo("new-label"));
    }

    @Test
    @DisplayName("updateLabels removes labels absent from new list")
    void updateLabelsRemovesStaleLabels() {
        MCPSystemLabel existing = new MCPSystemLabel("old-label", false, mcpSystem);
        Set<MCPSystemLabel> currentLabels = new LinkedHashSet<>(List.of(existing));

        mcpSystemService.updateLabels(mcpSystem, currentLabels, List.of());

        assertThat(currentLabels, empty());
    }

    @Test
    @DisplayName("updateLabels updates technical flag of existing labels")
    void updateLabelsUpdatesTechnicalFlag() {
        MCPSystemLabel existing = new MCPSystemLabel("label", false, mcpSystem);
        Set<MCPSystemLabel> currentLabels = new LinkedHashSet<>(List.of(existing));

        MCPSystemLabel updated = new MCPSystemLabel("label", true, mcpSystem);
        mcpSystemService.updateLabels(mcpSystem, currentLabels, List.of(updated));

        assertThat(currentLabels, hasSize(1));
        assertTrue(currentLabels.iterator().next().isTechnical());
    }

    @Test
    @DisplayName("updateLabels sets system reference on newly added labels")
    void updateLabelsSetsSystemOnNewLabels() {
        Set<MCPSystemLabel> currentLabels = new LinkedHashSet<>();
        MCPSystemLabel newLabel = new MCPSystemLabel();
        newLabel.setName("brand-new");

        mcpSystemService.updateLabels(mcpSystem, currentLabels, List.of(newLabel));

        assertThat(currentLabels.iterator().next().getSystem(), equalTo(mcpSystem));
    }

    // deleteById tests

    @Test
    @DisplayName("deleteById throws SystemDeleteException when system is used by a chain")
    void deleteByIdThrowsWhenUsedByChain() {
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(List.of(Chain.builder().id("chain-1").build()));

        assertThrows(SystemDeleteException.class, () -> mcpSystemService.deleteById(SYSTEM_ID));
        verify(mcpSystemRepository, never()).delete(any(MCPSystem.class));
    }

    @Test
    @DisplayName("deleteById deletes system and logs DELETE when not used by chains")
    void deleteByIdDeletesAndLogsWhenNotUsedByChain() {
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(List.of());
        when(mcpSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.of(mcpSystem));

        mcpSystemService.deleteById(SYSTEM_ID);

        verify(mcpSystemRepository).delete(mcpSystem);
        verify(actionLogger).logAction(actionLogCaptor.capture());
        assertThat(actionLogCaptor.getValue().getOperation(), equalTo(LogOperation.DELETE));
    }

    @Test
    @DisplayName("deleteById does nothing when system not found in repository")
    void deleteByIdDoesNothingWhenNotFound() {
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(List.of());
        when(mcpSystemRepository.findById(SYSTEM_ID)).thenReturn(Optional.empty());

        mcpSystemService.deleteById(SYSTEM_ID);

        verify(mcpSystemRepository, never()).delete(any(MCPSystem.class));
        verifyNoInteractions(actionLogger);
    }

    // filter tests

    @Test
    @DisplayName("filter builds specification and enriches result with chains")
    void filterBuildsSpecificationAndEnrichesWithChains() {
        Specification<MCPSystem> spec = mock(Specification.class);
        List<MCPSystem> systems = new ArrayList<>(List.of(mcpSystem));

        when(mcpSystemFilterSpecificationBuilder.buildSearchAndFilters(eq("search"), any()))
                .thenReturn(spec);
        when(mcpSystemRepository.findAll(spec)).thenReturn(systems);
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(List.of());

        List<MCPSystem> result = mcpSystemService.filter("search", List.of());

        assertThat(result, hasSize(1));
        verify(mcpSystemFilterSpecificationBuilder).buildSearchAndFilters(eq("search"), any());
    }

    // isUsedByChain tests

    @Test
    @DisplayName("isUsedByChain returns true when chains reference the system")
    void isUsedByChainReturnsTrueWhenChainExists() {
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(List.of(Chain.builder().id("chain-1").build()));

        assertTrue(mcpSystemService.isUsedByChain(SYSTEM_ID));
    }

    @Test
    @DisplayName("isUsedByChain returns false when no chains reference the system")
    void isUsedByChainReturnsFalseWhenNoChains() {
        when(chainRepository.findChainsWithElementPropertyContainsValue(MCP_SERVICE_IDS_PROPERTY, SYSTEM_ID))
                .thenReturn(List.of());

        assertFalse(mcpSystemService.isUsedByChain(SYSTEM_ID));
    }
}
