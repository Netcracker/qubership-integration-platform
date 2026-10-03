package org.qubership.integration.platform.maven.plugin.domain.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ImportEnvironmentImpl;
import org.qubership.integration.platform.chain.impl.ImportSystemImpl;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.chain.model.ServiceType;
import org.qubership.integration.platform.io.model.exportimport.system.IntegrationSystemType;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportSystemAdapterTest {

    @Test
    void exposesTheActiveEnvironmentAndItsProperties() throws Exception {
        ImportEnvironmentImpl active = environment("env-1", "https://active");
        active.setProperties(new ObjectMapper().readTree("{\"connectTimeout\": 5000}"));
        ImportEnvironmentImpl other = environment("env-2", "https://other");

        ImportSystemImpl system = new ImportSystemImpl();
        system.setId("system-1");
        system.setActiveEnvironmentId("env-1");
        system.setEnvironments(List.of(active, other));

        IntegrationService service = new ImportSystemAdapter(system);

        Optional<ServiceEnvironment> activeEnvironment = service.getActiveEnvironment();
        assertTrue(activeEnvironment.isPresent());
        assertEquals("env-1", activeEnvironment.get().getId());
        assertEquals("system-1", activeEnvironment.get().getSystemId());
        assertEquals(Map.of("connectTimeout", 5000), activeEnvironment.get().getProperties());
        assertEquals(Map.of(), service.getEnvironments().stream()
                .filter(e -> "env-2".equals(e.getId()))
                .findFirst()
                .orElseThrow()
                .getProperties());
    }

    @Test
    void translatesTypeProtocolAndLabels() {
        ImportSystemImpl system = new ImportSystemImpl();
        system.setIntegrationSystemType(IntegrationSystemType.EXTERNAL);
        system.setProtocol(OperationProtocol.HTTP);
        system.setLabels(List.of("team-a"));

        IntegrationService service = new ImportSystemAdapter(system);

        assertEquals(ServiceType.EXTERNAL, service.getType());
        assertEquals(Protocol.HTTP, service.getProtocol());
        Label label = service.getLabels().iterator().next();
        assertEquals("team-a", label.getName());
        assertFalse(label.isTechnical());
    }

    @Test
    void keepsAbsentTypeAndProtocolAbsent() {
        IntegrationService service = new ImportSystemAdapter(new ImportSystemImpl());

        assertNull(service.getType());
        assertNull(service.getProtocol());
        assertTrue(service.getActiveEnvironment().isEmpty());
    }

    private static ImportEnvironmentImpl environment(String id, String address) {
        ImportEnvironmentImpl environment = new ImportEnvironmentImpl();
        environment.setId(id);
        environment.setName(id);
        environment.setAddress(address);
        environment.setSourceType(EnvironmentSourceType.MANUAL);
        return environment;
    }
}
