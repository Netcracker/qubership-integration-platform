package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.SystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.SystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemControllerTest {

    private static final String SYSTEM_ID = "system-1";
    private static final String SYSTEM_NAME = "Orders service";

    @Mock SystemService systemService;
    @Mock ElementService elementService;

    private final SystemMapper systemMapper = Mappers.getMapper(SystemMapper.class);

    private SystemController controller;

    @BeforeEach
    void setUp() {
        controller = new SystemController(systemService, systemMapper, elementService);
    }

    @Test
    @DisplayName("PUT with a different type leaves the stored type alone")
    void putWithADifferentTypeDoesNotChangeTheStoredType() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(stored);
        when(systemService.save(stored)).thenReturn(stored);

        ResponseEntity<SystemDTO> response = controller.updateSystem(SYSTEM_ID, requestWith(IntegrationSystemType.INTERNAL));

        assertThat(stored.getIntegrationSystemType(), equalTo(IntegrationSystemType.EXTERNAL));
        assertThat(response.getBody().getType(), equalTo(IntegrationSystemType.EXTERNAL));
    }

    /**
     * The merge mapper is where a type change would land if anybody mapped the property, so pin the generated mapping
     * rather than relying on the controller test above to notice.
     */
    @Test
    @DisplayName("the merge mapper does not map the service type")
    void theMergeMapperDoesNotMapTheServiceType() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL);

        systemMapper.mergeWithoutLabels(requestWith(IntegrationSystemType.IMPLEMENTED), stored);

        assertThat(stored.getIntegrationSystemType(), equalTo(IntegrationSystemType.EXTERNAL));
        assertThat(stored.getName(), equalTo("Renamed service"));
    }

    /** PATCH shares the property set with PUT and must not open a second door onto the type. */
    @Test
    @DisplayName("the patch merge mapper does not map the service type")
    void thePatchMergeMapperDoesNotMapTheServiceType() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL);

        systemMapper.patchMergeWithoutLabels(requestWith(IntegrationSystemType.IMPLEMENTED), stored);

        assertThat(stored.getIntegrationSystemType(), equalTo(IntegrationSystemType.EXTERNAL));
    }

    @Test
    @DisplayName("PUT on an unknown id reports the id instead of creating a service")
    void putOnAnUnknownIdDoesNotCreateAService() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class,
                () -> controller.updateSystem(SYSTEM_ID, requestWith(IntegrationSystemType.INTERNAL)));

        assertThat(exception.getMessage(), containsString(SYSTEM_ID));
        verify(systemService, never()).create(any());
        verify(systemService, never()).create(any(), anyBoolean());
        verify(systemService, never()).save(any());
    }

    @Test
    @DisplayName("PUT on a known id still applies the mutable fields")
    void putOnAKnownIdAppliesTheMutableFields() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(stored);
        when(systemService.save(stored)).thenReturn(stored);

        controller.updateSystem(SYSTEM_ID, requestWith(IntegrationSystemType.EXTERNAL));

        assertThat(stored.getName(), equalTo("Renamed service"));
        assertThat(stored.getDescription(), equalTo("Renamed on update"));
        verify(systemService).updateSystemModelCompiledLibraryAsync(stored);
    }

    private static SystemRequestDTO requestWith(IntegrationSystemType type) {
        SystemRequestDTO request = new SystemRequestDTO();
        request.setName("Renamed service");
        request.setDescription("Renamed on update");
        request.setType(type);
        return request;
    }

    private static IntegrationSystem systemWith(IntegrationSystemType type) {
        return IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .integrationSystemType(type)
                .environments(new ArrayList<>())
                .build();
    }
}
