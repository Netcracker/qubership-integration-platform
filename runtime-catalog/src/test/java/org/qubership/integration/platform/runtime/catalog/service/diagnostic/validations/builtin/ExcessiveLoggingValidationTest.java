package org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.model.chain.SessionsLoggingLevel;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcessiveLoggingValidationTest {

    @Mock
    private ChainFinderService chainFinderService;
    @Mock
    private ConsulService consulService;

    @Test
    void configWithoutSessionsLoggingLevelDoesNotStopTheRemainingChecks() throws Exception {
        when(consulService.getChainRuntimeConfig()).thenReturn(Map.of(
                "no-level", DeploymentRuntimeProperties.builder().build(),
                "debug", DeploymentRuntimeProperties.builder().sessionsLoggingLevel(SessionsLoggingLevel.DEBUG).build()));
        Chain debugChain = Chain.builder().id("debug").build();
        when(chainFinderService.tryFindById("debug")).thenReturn(Optional.of(debugChain));

        Collection<ValidationChainAlert> alerts = new ExcessiveLoggingValidation(chainFinderService, consulService).validate();

        assertEquals(1, alerts.size());
        assertEquals(debugChain, alerts.iterator().next().getChain());
    }
}
