package org.qubership.integration.platform.runtime.catalog.model.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationSystemTypeTest {

    private static final Map<IntegrationSystemType, Set<OperationProtocol>> EXPECTED_PROTOCOLS = Map.of(
            IntegrationSystemType.INTERNAL, Set.of(OperationProtocol.values()),
            IntegrationSystemType.EXTERNAL, Arrays.stream(OperationProtocol.values())
                    .filter(protocol -> protocol != OperationProtocol.METAMODEL)
                    .collect(Collectors.toUnmodifiableSet()),
            IntegrationSystemType.IMPLEMENTED, Set.of(
                    OperationProtocol.HTTP,
                    OperationProtocol.SOAP,
                    OperationProtocol.GRAPHQL)
    );

    private static final Map<IntegrationSystemType, Integer> EXPECTED_MAX_ENVIRONMENTS = Map.of(
            IntegrationSystemType.INTERNAL, 1,
            IntegrationSystemType.EXTERNAL, Integer.MAX_VALUE,
            IntegrationSystemType.IMPLEMENTED, 1
    );

    @Test
    void externalAllowsEveryProtocolExceptMetamodel() {
        Set<OperationProtocol> protocols = IntegrationSystemType.EXTERNAL.allowedProtocols();

        assertFalse(protocols.contains(OperationProtocol.METAMODEL));
        assertEquals(OperationProtocol.values().length - 1, protocols.size());
    }

    @Test
    void internalAllowsEveryProtocol() {
        assertEquals(Set.of(OperationProtocol.values()), IntegrationSystemType.INTERNAL.allowedProtocols());
    }

    @Test
    void implementedAllowsOnlySynchronousProtocols() {
        assertEquals(
                Set.of(OperationProtocol.HTTP, OperationProtocol.SOAP, OperationProtocol.GRAPHQL),
                IntegrationSystemType.IMPLEMENTED.allowedProtocols());
    }

    @Test
    void internalAndImplementedAcceptOneEnvironment() {
        assertEquals(1, IntegrationSystemType.INTERNAL.maxEnvironments());
        assertEquals(1, IntegrationSystemType.IMPLEMENTED.maxEnvironments());
    }

    @Test
    void externalEnvironmentsAreUnbounded() {
        assertEquals(Integer.MAX_VALUE, IntegrationSystemType.EXTERNAL.maxEnvironments());
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void everyTypeStatesItsProtocolsAndEnvironmentLimit(IntegrationSystemType type) {
        assertTrue(EXPECTED_PROTOCOLS.containsKey(type), "no expected protocol set for " + type);
        assertTrue(EXPECTED_MAX_ENVIRONMENTS.containsKey(type), "no expected environment limit for " + type);

        assertEquals(EXPECTED_PROTOCOLS.get(type), type.allowedProtocols());
        assertEquals(EXPECTED_MAX_ENVIRONMENTS.get(type).intValue(), type.maxEnvironments());
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void allowedProtocolsAreNotModifiable(IntegrationSystemType type) {
        Set<OperationProtocol> protocols = type.allowedProtocols();

        assertThrows(UnsupportedOperationException.class, () -> protocols.add(OperationProtocol.METAMODEL));
    }
}
