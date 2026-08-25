package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemModelEqualsTest {

    @Test
    @DisplayName("MANUAL and DISCOVERED are distinct under strict comparison")
    void treatsManualAndDiscoveredAsDistinctWhenStrict() {
        SystemModel manual = model("m-1", SystemModelSource.MANUAL);
        SystemModel discovered = model("m-1", SystemModelSource.DISCOVERED);

        assertFalse(manual.equals(discovered, true));
        assertFalse(discovered.equals(manual, true));
    }

    @Test
    @DisplayName("MANUAL and DISCOVERED stay distinct under non-strict comparison")
    void treatsManualAndDiscoveredAsDistinctWhenNotStrict() {
        SystemModel manual = model("m-1", SystemModelSource.MANUAL);
        SystemModel discovered = model("m-2", SystemModelSource.DISCOVERED);

        assertFalse(manual.equals(discovered, false));
        assertFalse(discovered.equals(manual, false));
    }

    @Test
    @DisplayName("equals(Object) rejects a differing source")
    void rejectsDifferingSourceThroughSingleArgumentEquals() {
        assertNotEquals(model("m-1", SystemModelSource.MANUAL), model("m-1", SystemModelSource.DISCOVERED));
    }

    @Test
    @DisplayName("Models sharing a source are equal in both modes")
    void treatsMatchingSourcesAsEqualInBothModes() {
        SystemModel first = model("m-1", SystemModelSource.MANUAL);
        SystemModel second = model("m-1", SystemModelSource.MANUAL);

        assertTrue(first.equals(second, true));
        assertTrue(first.equals(second, false));
    }

    @Test
    @DisplayName("Non-strict comparison still ignores the id")
    void ignoresIdWhenNotStrict() {
        SystemModel first = model("m-1", SystemModelSource.MANUAL);
        SystemModel second = model("m-2", SystemModelSource.MANUAL);

        assertFalse(first.equals(second, true));
        assertTrue(first.equals(second, false));
    }

    private static SystemModel model(String id, SystemModelSource source) {
        return SystemModel.builder()
                .id(id)
                .name("model")
                .description("description")
                .version("v1")
                .source(source)
                .build();
    }
}
