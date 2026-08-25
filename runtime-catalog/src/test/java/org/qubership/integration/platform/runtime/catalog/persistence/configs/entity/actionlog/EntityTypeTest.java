package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypeTest {

    private static final Map<IntegrationSystemType, EntityType> EXPECTED_ENTITY_TYPES = Map.of(
            IntegrationSystemType.INTERNAL, EntityType.INNER_CLOUD_SERVICE,
            IntegrationSystemType.EXTERNAL, EntityType.EXTERNAL_SERVICE,
            IntegrationSystemType.IMPLEMENTED, EntityType.IMPLEMENTED_SERVICE
    );

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void everyServiceTypeMapsToItsOwnEntityType(IntegrationSystemType type) {
        assertTrue(EXPECTED_ENTITY_TYPES.containsKey(type), "no expected entity type for " + type);

        assertEquals(EXPECTED_ENTITY_TYPES.get(type), EntityType.getSystemType(systemOfType(type)));
    }

    /** Any refusal will do; the point is that no entity type is invented for a service that states none. */
    @Test
    void aTypelessServiceIsNotSilentlyReportedAsExternal() {
        assertThrows(RuntimeException.class, () -> EntityType.getSystemType(systemOfType(null)));
    }

    private static IntegrationSystem systemOfType(IntegrationSystemType type) {
        return IntegrationSystem.builder().integrationSystemType(type).build();
    }
}
