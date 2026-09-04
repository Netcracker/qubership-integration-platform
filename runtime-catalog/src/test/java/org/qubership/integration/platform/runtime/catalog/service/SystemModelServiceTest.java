package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SpecificationGroup} cascades {@code ALL} to its models, so a model deleted while still in
 * its group's collection is re-persisted by the flush-time cascade.
 */
@ExtendWith(MockitoExtension.class)
class SystemModelServiceTest {

    private static final String MODEL_ID = "group-1-1.0.0";

    @Mock
    private SystemModelRepository systemModelRepository;
    @Mock
    private SystemModelLabelsRepository systemModelLabelsRepository;
    @Mock
    private ElementHelperService elementHelperService;
    @Mock
    private ActionsLogService actionLogger;

    @InjectMocks
    private SystemModelService systemModelService;

    @Test
    @DisplayName("deleteSystemModelByIdIfExists unlinks the specification from its group")
    void deleteSystemModelByIdIfExistsUnlinksTheSpecificationFromItsGroup() {
        SpecificationGroup group = new SpecificationGroup();
        group.setId("group-1");
        SystemModel model = new SystemModel();
        model.setId(MODEL_ID);
        group.addSystemModel(model);
        when(systemModelRepository.findById(MODEL_ID)).thenReturn(Optional.of(model));
        when(elementHelperService.isSystemModelUsedByElement(MODEL_ID)).thenReturn(false);

        Optional<SystemModel> deleted = systemModelService.deleteSystemModelByIdIfExists(MODEL_ID);

        assertThat(deleted).contains(model);
        assertThat(group.getSystemModels())
                .as("a model left in the group's collection is re-persisted by the cascade")
                .isEmpty();
        verify(systemModelRepository).delete(model);
    }

    @Test
    @DisplayName("deleteSystemModelByIdIfExists does nothing when the specification is gone")
    void deleteSystemModelByIdIfExistsDoesNothingWhenTheSpecificationIsGone() {
        when(systemModelRepository.findById(MODEL_ID)).thenReturn(Optional.empty());

        assertThat(systemModelService.deleteSystemModelByIdIfExists(MODEL_ID)).isEmpty();

        verify(systemModelRepository, never()).delete(any(SystemModel.class));
    }
}
