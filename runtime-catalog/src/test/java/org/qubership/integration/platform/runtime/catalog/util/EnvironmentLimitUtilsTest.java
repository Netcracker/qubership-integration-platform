package org.qubership.integration.platform.runtime.catalog.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentLimitUtilsTest {

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("one environment is accepted for every type")
    void oneEnvironmentIsAccepted(IntegrationSystemType type) {
        assertDoesNotThrow(() -> EnvironmentLimitUtils.validate(systemOfType(type), 1));
    }

    @ParameterizedTest
    @EnumSource(value = IntegrationSystemType.class, names = {"INTERNAL", "IMPLEMENTED"})
    @DisplayName("a second environment is rejected for a single-environment type")
    void secondEnvironmentIsRejected(IntegrationSystemType type) {
        assertThrows(BadRequestException.class, () -> EnvironmentLimitUtils.validate(systemOfType(type), 2));
    }

    @Test
    @DisplayName("an external service takes as many environments as it likes")
    void externalIsUnbounded() {
        assertDoesNotThrow(() -> EnvironmentLimitUtils.validate(systemOfType(IntegrationSystemType.EXTERNAL), 1000));
    }

    @Test
    @DisplayName("a service row with no type is not checked against any limit")
    void typelessServiceIsNotChecked() {
        assertDoesNotThrow(() -> EnvironmentLimitUtils.validate(systemOfType(null), 5));
    }

    @Test
    @DisplayName("the rejection names the service and both counts")
    void rejectionNamesTheServiceAndBothCounts() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id("service-1")
                .name("Billing")
                .integrationSystemType(IntegrationSystemType.INTERNAL)
                .build();

        BadRequestException exception =
                assertThrows(BadRequestException.class, () -> EnvironmentLimitUtils.validate(system, 3));

        assertTrue(exception.getMessage().contains("service-1"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Billing"), exception.getMessage());
        assertTrue(exception.getMessage().contains("at most 1 environment,"), exception.getMessage());
        assertTrue(exception.getMessage().contains("3 were given"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Remove the extra environments"), exception.getMessage());
    }

    /** The export path reads the violation instead of throwing on it, so both forms answer the same question. */
    @ParameterizedTest
    @EnumSource(value = IntegrationSystemType.class, names = {"INTERNAL", "IMPLEMENTED"})
    @DisplayName("the reported violation agrees with the thrown one")
    void violationAgreesWithValidate(IntegrationSystemType type) {
        assertTrue(EnvironmentLimitUtils.violation(systemOfType(type), 2).isPresent());
        assertTrue(EnvironmentLimitUtils.violation(systemOfType(type), 1).isEmpty());
    }

    @Test
    @DisplayName("no violation is reported for an external or a typeless service")
    void noViolationForExternalOrTypeless() {
        assertTrue(EnvironmentLimitUtils.violation(systemOfType(IntegrationSystemType.EXTERNAL), 1000).isEmpty());
        assertTrue(EnvironmentLimitUtils.violation(systemOfType(null), 5).isEmpty());
    }

    private static IntegrationSystem systemOfType(IntegrationSystemType type) {
        return IntegrationSystem.builder().integrationSystemType(type).build();
    }
}
