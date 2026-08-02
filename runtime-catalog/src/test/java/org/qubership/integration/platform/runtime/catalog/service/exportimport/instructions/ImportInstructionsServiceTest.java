package org.qubership.integration.platform.runtime.catalog.service.exportimport.instructions;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ImportInstructionsValidationException;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportEntityType;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionAction;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.CommonVariablesInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.GeneralInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions.ServiceInstructionsMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.instructions.ImportInstructionsRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.ApiGroupService;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.filter.ImportInstructionFilterSpecificationBuilder;
import org.qubership.integration.platform.runtime.catalog.validation.EntityValidator;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportInstructionsServiceTest {

    @Mock
    ImportInstructionsRepository importInstructionsRepository;
    @Mock
    GeneralInstructionsMapper generalInstructionsMapper;
    @Mock
    ServiceInstructionsMapper serviceInstructionsMapper;
    @Mock
    ChainService chainService;
    @Mock
    DeploymentService deploymentService;
    @Mock
    SystemService systemService;
    @Mock
    ApiGroupService apiGroupService;
    @Mock
    SystemModelService systemModelService;
    @Mock
    CommonVariablesInstructionsMapper commonVariablesInstructionsMapper;
    @Mock
    EntityValidator entityValidator;
    @Mock
    ActionsLogService actionsLogService;
    @Mock
    ImportInstructionFilterSpecificationBuilder importInstructionFilterSpecificationBuilder;
    @Mock
    ConsulService consulService;

    ImportInstructionsService importInstructionsService;

    @BeforeEach
    void setUp() {
        importInstructionsService = new ImportInstructionsService(
                "import-instructions",
                importInstructionsRepository,
                generalInstructionsMapper,
                serviceInstructionsMapper,
                chainService,
                deploymentService,
                systemService,
                apiGroupService,
                systemModelService,
                commonVariablesInstructionsMapper,
                entityValidator,
                actionsLogService,
                importInstructionFilterSpecificationBuilder,
                consulService
        );
    }

    @Test
    @DisplayName("exportImportInstructions keeps the API groups section comment after the field rename")
    void exportKeepsApiGroupsSectionComment() {
        GeneralImportInstructionsConfig config = GeneralImportInstructionsConfig.builder()
                .apiGroups(ImportInstructionsConfig.builder().delete(Set.of("group-1")).build())
                .build();
        when(importInstructionsRepository.findAll()).thenReturn(Collections.emptyList());
        when(generalInstructionsMapper.asConfig(anyList())).thenReturn(config);

        Pair<String, byte[]> result = importInstructionsService.exportImportInstructions();

        String yaml = new String(result.getRight());
        assertThat(yaml)
                .contains("# API groups section might contain only delete action")
                .contains("apiGroups:")
                .doesNotContain("specificationGroups:");
    }

    @Test
    @DisplayName("an API group instruction rejects the IGNORE and OVERRIDE actions")
    void apiGroupInstructionRejectsIgnoreAndOverride() {
        for (ImportInstructionAction action : List.of(ImportInstructionAction.IGNORE, ImportInstructionAction.OVERRIDE)) {
            ImportInstruction instruction = ImportInstruction.builder()
                    .id("group-1")
                    .entityType(ImportEntityType.API_GROUP)
                    .action(action)
                    .build();

            // The message reaches the v1 client in the error response body, so it is pinned word for word.
            assertThatThrownBy(() -> importInstructionsService.addImportInstruction(instruction))
                    .isInstanceOf(ImportInstructionsValidationException.class)
                    .hasMessage("Specification Group instruction does not support action IGNORE and OVERRIDE");
        }
    }

    @Test
    @DisplayName("an API group instruction accepts the DELETE action")
    void apiGroupInstructionAcceptsDelete() {
        ImportInstruction instruction = ImportInstruction.builder()
                .id("group-1")
                .entityType(ImportEntityType.API_GROUP)
                .action(ImportInstructionAction.DELETE)
                .build();
        when(importInstructionsRepository.existsById("group-1")).thenReturn(false);
        when(importInstructionsRepository.persistAndReturn(instruction)).thenReturn(instruction);

        assertThat(importInstructionsService.addImportInstruction(instruction)).isSameAs(instruction);
    }
}
