package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemBaseServiceTest {

    @Mock
    private SystemRepository systemRepository;
    @Mock
    private ActionsLogService actionsLogger;
    @Mock
    private IntegrationSystemLabelsRepository systemLabelsRepository;

    @InjectMocks
    private SystemBaseService systemBaseService;

    @Test
    @DisplayName("A service with no type is rejected before anything is written")
    void createRejectsAServiceWithoutType() {
        IntegrationSystem system = new IntegrationSystem();
        system.setName("probe");

        assertThatThrownBy(() -> systemBaseService.create(system))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Service type is not specified")
                .hasMessageContaining("EXTERNAL");

        verifyNoInteractions(systemRepository, actionsLogger);
    }

    @Test
    @DisplayName("A typed service is saved and logged under the type it declares")
    void createSavesATypedService() {
        IntegrationSystem system = new IntegrationSystem();
        system.setId("system-1");
        system.setName("probe");
        system.setIntegrationSystemType(IntegrationSystemType.IMPLEMENTED);
        when(systemRepository.save(system)).thenReturn(system);

        IntegrationSystem saved = systemBaseService.create(system);

        assertThat(saved).isSameAs(system);
        ArgumentCaptor<ActionLog> logged = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionsLogger).logAction(logged.capture());
        assertThat(logged.getValue().getEntityType()).isEqualTo(EntityType.IMPLEMENTED_SERVICE);
    }
}
