package org.qubership.integration.platform.runtime.catalog.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentAdapterTest {

    @DisplayName("Should pass through basic fields and convert jsonb properties to a Map")
    @Test
    void shouldExposeBasicFieldsAndConvertProperties() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").build();
        JsonNode properties = new ObjectMapper().valueToTree(Map.of("key", "value"));
        Environment environment = Environment.builder()
                .id("env-1")
                .name("Env 1")
                .description("some description")
                .address("http://example.com")
                .sourceType(EnvironmentSourceType.MANUAL)
                .system(system)
                .properties(properties)
                .build();

        EnvironmentAdapter adapter = new EnvironmentAdapter(environment);

        assertEquals("env-1", adapter.getId());
        assertEquals("Env 1", adapter.getName());
        assertEquals("some description", adapter.getDescription());
        assertEquals("http://example.com", adapter.getAddress());
        assertEquals(EnvironmentSourceType.MANUAL, adapter.getSourceType());
        assertEquals("sys-1", adapter.getSystemId());
        assertEquals(Map.of("key", "value"), adapter.getProperties());
    }

    @DisplayName("Should report activated when the active environment id is the same String reference")
    @Test
    void shouldReportActivatedForSameStringReference() {
        String sharedId = "env-1";
        IntegrationSystem system = IntegrationSystem.builder().activeEnvironmentId(sharedId).build();
        Environment environment = Environment.builder().id(sharedId).system(system).build();

        EnvironmentAdapter adapter = new EnvironmentAdapter(environment);

        assertTrue(adapter.isActivated());
    }

    @DisplayName("Should report activated when the active environment id has an equal value but is a different String instance")
    @Test
    void shouldReportActivatedForEqualButDistinctStringInstance() {
        String environmentId = "env-1";
        IntegrationSystem system = IntegrationSystem.builder().activeEnvironmentId(new String(environmentId)).build();
        Environment environment = Environment.builder().id(environmentId).system(system).build();

        EnvironmentAdapter adapter = new EnvironmentAdapter(environment);

        assertTrue(adapter.isActivated());
    }

    @DisplayName("Should report not activated when the active environment id differs from the environment id")
    @Test
    void shouldReportNotActivatedWhenIdsDiffer() {
        IntegrationSystem system = IntegrationSystem.builder().activeEnvironmentId("some-other-env").build();
        Environment environment = Environment.builder().id("env-1").system(system).build();

        EnvironmentAdapter adapter = new EnvironmentAdapter(environment);

        assertFalse(adapter.isActivated());
    }

    @DisplayName("Should report not activated when the system has no active environment id")
    @Test
    void shouldReportNotActivatedWhenActiveEnvironmentIdIsNull() {
        IntegrationSystem system = IntegrationSystem.builder().activeEnvironmentId(null).build();
        Environment environment = Environment.builder().id("env-1").system(system).build();

        EnvironmentAdapter adapter = new EnvironmentAdapter(environment);

        assertFalse(adapter.isActivated());
    }
}
