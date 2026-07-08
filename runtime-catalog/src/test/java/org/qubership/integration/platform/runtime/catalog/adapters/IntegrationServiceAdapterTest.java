package org.qubership.integration.platform.runtime.catalog.adapters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.Label;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.chain.model.ServiceType;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationServiceAdapterTest {

    @DisplayName("Should map basic fields, type and protocol")
    @Test
    void shouldMapBasicFieldsTypeAndProtocol() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id("sys-1")
                .name("Sys 1")
                .description("some description")
                .integrationSystemType(IntegrationSystemType.INTERNAL)
                .protocol(OperationProtocol.HTTP)
                .build();

        IntegrationServiceAdapter adapter = new IntegrationServiceAdapter(system);

        assertEquals("sys-1", adapter.getId());
        assertEquals("Sys 1", adapter.getName());
        assertEquals("some description", adapter.getDescription());
        assertEquals(ServiceType.INTERNAL, adapter.getType());
        assertEquals(Protocol.HTTP, adapter.getProtocol());
    }

    @DisplayName("Should wrap all environments")
    @Test
    void shouldWrapEnvironments() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").build();
        Environment env1 = Environment.builder().id("env-1").system(system).build();
        Environment env2 = Environment.builder().id("env-2").system(system).build();
        system.setEnvironments(List.of(env1, env2));

        IntegrationServiceAdapter adapter = new IntegrationServiceAdapter(system);

        assertEquals(2, adapter.getEnvironments().size());
        assertTrue(adapter.getEnvironments().stream().map(ServiceEnvironment::getId).toList().containsAll(List.of("env-1", "env-2")));
    }

    @DisplayName("Should return the environment matching the active environment id")
    @Test
    void shouldReturnActiveEnvironmentWhenIdMatches() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").activeEnvironmentId("env-2").build();
        Environment env1 = Environment.builder().id("env-1").system(system).build();
        Environment env2 = Environment.builder().id("env-2").system(system).build();
        system.setEnvironments(List.of(env1, env2));

        IntegrationServiceAdapter adapter = new IntegrationServiceAdapter(system);

        Optional<ServiceEnvironment> activeEnvironment = adapter.getActiveEnvironment();
        assertTrue(activeEnvironment.isPresent());
        assertEquals("env-2", activeEnvironment.get().getId());
    }

    @DisplayName("Should return an empty active environment when no environment id matches")
    @Test
    void shouldReturnEmptyActiveEnvironmentWhenNoneMatches() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").activeEnvironmentId("does-not-exist").build();
        Environment env1 = Environment.builder().id("env-1").system(system).build();
        system.setEnvironments(List.of(env1));

        IntegrationServiceAdapter adapter = new IntegrationServiceAdapter(system);

        assertTrue(adapter.getActiveEnvironment().isEmpty());
    }

    @DisplayName("Should wrap labels")
    @Test
    void shouldWrapLabels() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").build();
        system.setLabels(Set.of(new IntegrationSystemLabel("label-a", system), new IntegrationSystemLabel("label-b", system)));

        IntegrationServiceAdapter adapter = new IntegrationServiceAdapter(system);

        assertEquals(2, adapter.getLabels().size());
        assertTrue(adapter.getLabels().stream().map(Label::getName).toList().containsAll(List.of("label-a", "label-b")));
    }
}
