package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.exportimport.instructions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.GeneralImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionsDTO;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralInstructionsMapperTest {

    @Mock
    ChainInstructionsMapper chainInstructionsMapper;
    @Mock
    ServiceInstructionsMapper serviceInstructionsMapper;
    @Mock
    ApiGroupInstructionsMapper apiGroupInstructionsMapper;
    @Mock
    SpecificationInstructionsMapper specificationInstructionsMapper;
    @Mock
    CommonVariablesInstructionsMapper commonVariablesInstructionsMapper;

    @InjectMocks
    GeneralInstructionsMapper generalInstructionsMapper;

    @Test
    @DisplayName("asConfig puts API group instructions under the renamed apiGroups field")
    void asConfigMapsApiGroups() {
        ImportInstructionsConfig apiGroupsConfig = ImportInstructionsConfig.builder().delete(Set.of("group-1")).build();
        when(apiGroupInstructionsMapper.asConfig(anyList())).thenReturn(apiGroupsConfig);

        GeneralImportInstructionsConfig config = generalInstructionsMapper.asConfig(Collections.emptyList());

        assertThat(config.getApiGroups()).isEqualTo(apiGroupsConfig);
    }

    @Test
    @DisplayName("asDTO still exposes API group instructions under the v1 specificationGroups property")
    void asDtoKeepsV1PropertyName() {
        ImportInstructionsDTO apiGroupsDto = ImportInstructionsDTO.builder().build();
        when(apiGroupInstructionsMapper.asDTO(anyList())).thenReturn(apiGroupsDto);

        GeneralImportInstructionsDTO dto = generalInstructionsMapper.asDTO(List.of());

        assertThat(dto.getSpecificationGroups()).isEqualTo(apiGroupsDto);
    }
}
