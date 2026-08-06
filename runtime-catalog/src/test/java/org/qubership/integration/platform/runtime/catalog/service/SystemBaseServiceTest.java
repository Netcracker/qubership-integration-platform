package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.IntegrationSystemLabelsRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SystemBaseServiceTest {

    private final SystemBaseService service = new SystemBaseService(
            mock(SystemRepository.class),
            mock(ActionsLogService.class),
            mock(IntegrationSystemLabelsRepository.class));

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("every protocol the type allows passes validation")
    void allowedProtocolsPass(IntegrationSystemType type) {
        IntegrationSystem system = systemOfType(type);

        type.allowedProtocols()
                .forEach(protocol -> assertDoesNotThrow(() -> service.validateSpecificationProtocol(system, protocol)));
    }

    @Test
    @DisplayName("METAMODEL is rejected for an external service")
    void metamodelIsRejectedForExternal() {
        IntegrationSystem system = systemOfType(IntegrationSystemType.EXTERNAL);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.validateSpecificationProtocol(system, OperationProtocol.METAMODEL));

        assertTrue(exception.getMessage().contains("external"), exception.getMessage());
    }

    @Test
    @DisplayName("KAFKA is rejected for an implemented service")
    void kafkaIsRejectedForImplemented() {
        IntegrationSystem system = systemOfType(IntegrationSystemType.IMPLEMENTED);

        assertThrows(RuntimeException.class,
                () -> service.validateSpecificationProtocol(system, OperationProtocol.KAFKA));
    }

    @Test
    @DisplayName("a service row with no type is reported rather than dereferenced")
    void typelessServiceIsReported() {
        IntegrationSystem system = IntegrationSystem.builder().id("service-1").build();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.validateSpecificationProtocol(system, OperationProtocol.HTTP));

        assertTrue(exception.getMessage().contains("service-1"), exception.getMessage());
    }

    @Test
    @DisplayName("no protocol is always accepted")
    void noProtocolIsAccepted() {
        assertDoesNotThrow(() -> service.validateSpecificationProtocol(systemOfType(null), null));
    }

    private static IntegrationSystem systemOfType(IntegrationSystemType type) {
        return IntegrationSystem.builder().integrationSystemType(type).build();
    }
}
