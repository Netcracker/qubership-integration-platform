package org.qubership.integration.platform.maven.plugin.domain.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.IntegrationService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationServiceCatalogImplTest {

    private final IntegrationServiceCatalogImpl catalog = new IntegrationServiceCatalogImpl();

    @Test
    void findsAnAddedServiceById() {
        IntegrationService service = service("system-1");

        catalog.addService(service);

        assertSame(service, catalog.findById("system-1").orElseThrow());
        assertTrue(catalog.findById("system-2").isEmpty());
    }

    @Test
    void findsSeveralServicesInTheRequestedOrder() {
        IntegrationService first = service("system-1");
        IntegrationService second = service("system-2");
        catalog.addService(first);
        catalog.addService(second);

        assertEquals(List.of(second, first), catalog.findAllByIds(List.of("system-2", "system-1")));
    }

    @Test
    void rejectsASecondServiceWithTheSameId() {
        catalog.addService(service("system-1"));

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> catalog.addService(service("system-1")));

        assertTrue(exception.getMessage().contains("system-1"));
    }

    private static IntegrationService service(String id) {
        IntegrationService service = mock(IntegrationService.class);
        when(service.getId()).thenReturn(id);
        return service;
    }
}
