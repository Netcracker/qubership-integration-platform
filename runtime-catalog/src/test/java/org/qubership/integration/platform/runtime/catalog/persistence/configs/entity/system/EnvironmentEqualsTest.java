package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EnvironmentBaseService.resolveEnvironmentsForServers} matches a freshly built environment against the
 * stored ones with {@code equals(env, false)}, so the id must not take part while every other field does.
 */
class EnvironmentEqualsTest {

    @Test
    @DisplayName("Non-strict comparison ignores the id")
    void ignoresIdWhenNotStrict() {
        Environment stored = environment("e-1", "http://host:8080", "env");
        Environment candidate = environment("e-2", "http://host:8080", "env");

        assertTrue(stored.equals(candidate, false));
        assertFalse(stored.equals(candidate, true));
    }

    @Test
    @DisplayName("Non-strict comparison still compares the address")
    void comparesAddressWhenNotStrict() {
        Environment stored = environment("e-1", "http://host:8080", "env");
        Environment candidate = environment("e-2", "http://other:8080", "env");

        assertFalse(stored.equals(candidate, false));
    }

    @Test
    @DisplayName("Non-strict comparison still compares the name")
    void comparesNameWhenNotStrict() {
        Environment stored = environment("e-1", "http://host:8080", "env");
        Environment candidate = environment("e-2", "http://host:8080", "other");

        assertFalse(stored.equals(candidate, false));
    }

    @Test
    @DisplayName("Non-strict comparison still compares the source type")
    void comparesSourceTypeWhenNotStrict() {
        Environment stored = environment("e-1", "http://host:8080", "env");
        Environment candidate = environment("e-2", "http://host:8080", "env");
        candidate.setSourceType(EnvironmentSourceType.MAAS_BY_CLASSIFIER);

        assertFalse(stored.equals(candidate, false));
    }

    @Test
    @DisplayName("Non-strict comparison still compares the labels")
    void comparesLabelsWhenNotStrict() {
        Environment stored = environment("e-1", "http://host:8080", "env");
        Environment candidate = environment("e-2", "http://host:8080", "env");
        candidate.setLabels(new ArrayList<>(List.of(EnvironmentLabel.PRODUCTION)));

        assertFalse(stored.equals(candidate, false));
    }

    /** {@code properties} is the jsonb column the MaaS classifier scope lives in. */
    @Test
    @DisplayName("Non-strict comparison still compares the properties")
    void comparesPropertiesWhenNotStrict() {
        Environment stored = environment("e-1", "http://host:8080", "env");
        Environment candidate = environment("e-2", "http://host:8080", "env");
        candidate.setProperties(new ObjectMapper().createObjectNode().put("maas.classifier.name", "orders"));

        assertFalse(stored.equals(candidate, false));
    }

    private static Environment environment(String id, String address, String name) {
        return Environment.builder()
                .id(id)
                .name(name)
                .address(address)
                .sourceType(EnvironmentSourceType.MANUAL)
                .labels(new ArrayList<>())
                .build();
    }
}
