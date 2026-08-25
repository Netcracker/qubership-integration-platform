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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.runtime.catalog.testutils.ServiceFixtures.SYSTEM_ID;
import static org.qubership.integration.platform.runtime.catalog.testutils.ServiceFixtures.systemWith;

@ExtendWith(MockitoExtension.class)
class SystemControllerTest {

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
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL, 0);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(stored);
        when(systemService.save(stored)).thenReturn(stored);

        ResponseEntity<SystemDTO> response = controller.updateSystem(SYSTEM_ID, requestWith(IntegrationSystemType.INTERNAL));

        assertEquals(IntegrationSystemType.EXTERNAL, stored.getIntegrationSystemType());
        assertEquals(IntegrationSystemType.EXTERNAL, response.getBody().getType());
    }

    /**
     * The merge mapper is where a type change would land if anybody mapped the property, so pin the generated mapping
     * rather than relying on the controller test above to notice.
     */
    @Test
    @DisplayName("the merge mapper does not map the service type")
    void theMergeMapperDoesNotMapTheServiceType() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL, 0);

        systemMapper.mergeWithoutLabels(requestWith(IntegrationSystemType.IMPLEMENTED), stored);

        assertEquals(IntegrationSystemType.EXTERNAL, stored.getIntegrationSystemType());
        assertEquals("Renamed service", stored.getName());
    }

    /** PATCH shares the property set with PUT and must not open a second door onto the type. */
    @Test
    @DisplayName("the patch merge mapper does not map the service type")
    void thePatchMergeMapperDoesNotMapTheServiceType() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL, 0);

        systemMapper.patchMergeWithoutLabels(requestWith(IntegrationSystemType.IMPLEMENTED), stored);

        assertEquals(IntegrationSystemType.EXTERNAL, stored.getIntegrationSystemType());
    }

    @Test
    @DisplayName("PUT on an unknown id reports the id instead of creating a service")
    void putOnAnUnknownIdDoesNotCreateAService() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class,
                () -> controller.updateSystem(SYSTEM_ID, requestWith(IntegrationSystemType.INTERNAL)));

        assertMessageContains(exception, SYSTEM_ID);
        verify(systemService, never()).create(any());
        verify(systemService, never()).create(any(), anyBoolean());
        verify(systemService, never()).save(any());
    }

    /** PATCH shares the request DTO and the id, so it reports an unknown one the way PUT does. */
    @Test
    @DisplayName("PATCH on an unknown id reports the id instead of answering a bodiless 400")
    void patchOnAnUnknownIdReportsTheId() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class,
                () -> controller.updateSyncStatus(SYSTEM_ID, requestWith(IntegrationSystemType.INTERNAL)));

        assertMessageContains(exception, SYSTEM_ID);
        verify(systemService, never()).save(any());
    }

    @Test
    @DisplayName("PUT on a known id still applies the mutable fields")
    void putOnAKnownIdAppliesTheMutableFields() {
        IntegrationSystem stored = systemWith(IntegrationSystemType.EXTERNAL, 0);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(stored);
        when(systemService.save(stored)).thenReturn(stored);

        controller.updateSystem(SYSTEM_ID, requestWith(IntegrationSystemType.EXTERNAL));

        assertEquals("Renamed service", stored.getName());
        assertEquals("Renamed on update", stored.getDescription());
        verify(systemService).updateSystemModelCompiledLibraryAsync(stored);
    }

    private static void assertMessageContains(Exception exception, String expected) {
        assertTrue(exception.getMessage().contains(expected),
                () -> "expected the message to contain '" + expected + "', got: " + exception.getMessage());
    }

    private static SystemRequestDTO requestWith(IntegrationSystemType type) {
        SystemRequestDTO request = new SystemRequestDTO();
        request.setName("Renamed service");
        request.setDescription("Renamed on update");
        request.setType(type);
        return request;
    }
}
