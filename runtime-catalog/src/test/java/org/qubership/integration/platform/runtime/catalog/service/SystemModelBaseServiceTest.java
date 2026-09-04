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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SpecificationGroup} cascades {@code ALL} to its models, so deleting a model that is still
 * in its group's collection is un-scheduled by the flush-time cascade and the row survives. These
 * tests pin the unlink that prevents it.
 */
@ExtendWith(MockitoExtension.class)
class SystemModelBaseServiceTest {

    private static final String MODEL_ID = "group-1-1.0.0";

    @Mock
    private SystemModelRepository systemModelRepository;
    @Mock
    private SystemModelLabelsRepository systemModelLabelsRepository;
    @Mock
    private ActionsLogService actionLogger;

    @InjectMocks
    private SystemModelBaseService systemModelService;

    @Test
    @DisplayName("delete unlinks the specification from its group before deleting it")
    void deleteUnlinksTheSpecificationFromItsGroup() {
        SpecificationGroup group = new SpecificationGroup();
        group.setId("group-1");
        SystemModel persistedModel = new SystemModel();
        persistedModel.setId(MODEL_ID);
        group.addSystemModel(persistedModel);

        SystemModel detachedModel = new SystemModel();
        detachedModel.setId(MODEL_ID);
        when(systemModelRepository.findById(MODEL_ID)).thenReturn(Optional.of(persistedModel));

        systemModelService.delete(detachedModel);

        assertThat(group.getSystemModels())
                .as("a model left in the group's collection is re-persisted by the cascade")
                .isEmpty();
        assertThat(persistedModel.getSpecificationGroup()).isNull();
        verify(systemModelRepository).delete(persistedModel);
    }

    @Test
    @DisplayName("delete removes a specification that belongs to no group")
    void deleteRemovesASpecificationWithNoGroup() {
        SystemModel persistedModel = new SystemModel();
        persistedModel.setId(MODEL_ID);
        when(systemModelRepository.findById(MODEL_ID)).thenReturn(Optional.of(persistedModel));

        systemModelService.delete(persistedModel);

        verify(systemModelRepository).delete(persistedModel);
    }

    @Test
    @DisplayName("delete ignores a null specification")
    void deleteIgnoresANullSpecification() {
        systemModelService.delete(null);

        verify(systemModelRepository, never()).findById(any());
        verify(systemModelRepository, never()).delete(any(SystemModel.class));
    }

    @Test
    @DisplayName("delete does nothing when the specification is already gone")
    void deleteDoesNothingWhenTheSpecificationIsAlreadyGone() {
        SystemModel detachedModel = new SystemModel();
        detachedModel.setId(MODEL_ID);
        when(systemModelRepository.findById(MODEL_ID)).thenReturn(Optional.empty());

        systemModelService.delete(detachedModel);

        verify(systemModelRepository, never()).delete(any(SystemModel.class));
    }
}
