package org.qubership.integration.platform.runtime.catalog.cr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.consul.KeyResponse;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLeaderKeysCleanupTest {

    private static final String LEADERS = "config/qip-e2e/cip-engine-configurations/scheduler-leaders/";

    private ConsulService consulService;
    private KubeOperator kubeOperator;
    private SchedulerLeaderKeysCleanup cleanup;

    @BeforeEach
    void setUp() {
        consulService = mock(ConsulService.class);
        kubeOperator = mock(KubeOperator.class);
        cleanup = new SchedulerLeaderKeysCleanup(consulService, kubeOperator);
        cleanup.bgVersionLabel = "bg-version";
        cleanup.bgVersion = "v1";
    }

    @Test
    void shouldDeleteAReleasedKeyOfADomainThatNoIntegrationRuns() {
        KeyResponse deleted = key("orders-v1", null);
        integrations();
        when(consulService.getSchedulerLeaderKeys()).thenReturn(List.of(deleted));
        when(consulService.deleteSchedulerLeaderKey(deleted)).thenReturn(true);

        cleanup.deleteKeysOfDeletedDomains();

        verify(consulService).deleteSchedulerLeaderKey(deleted);
    }

    @Test
    void shouldKeepTheKeyOfADomainThatAnIntegrationStillRuns() {
        integrations(integration("payments"));
        when(consulService.getSchedulerLeaderKeys()).thenReturn(List.of(key("payments-v1", null)));

        cleanup.deleteKeysOfDeletedDomains();

        verify(consulService, never()).deleteSchedulerLeaderKey(any());
    }

    @Test
    void shouldKeepAKeyThatASessionHolds() {
        integrations();
        when(consulService.getSchedulerLeaderKeys())
                .thenReturn(List.of(key("orders-v1", "8d4d10b6-9b8c-4ad0-a133-fb85cf9d2865")));

        cleanup.deleteKeysOfDeletedDomains();

        verify(consulService, never()).deleteSchedulerLeaderKey(any());
    }

    @Test
    void shouldKeepTheKeyOfAnotherBlueGreenVersion() {
        integrations();
        when(consulService.getSchedulerLeaderKeys()).thenReturn(List.of(key("orders-v2", null)));

        cleanup.deleteKeysOfDeletedDomains();

        verify(consulService, never()).deleteSchedulerLeaderKey(any());
    }

    @Test
    void shouldDeleteNothingWhenTheIntegrationsCannotBeListed() {
        when(kubeOperator.getIntegrationsByLabels(Map.of("bg-version", "v1")))
                .thenThrow(new KubeApiException("Failed to get Camel K integrations"));

        cleanup.deleteKeysOfDeletedDomains();

        verify(consulService, never()).deleteSchedulerLeaderKey(any());
    }

    private void integrations(CamelKIntegration... integrations) {
        when(kubeOperator.getIntegrationsByLabels(Map.of("bg-version", "v1"))).thenReturn(List.of(integrations));
    }

    private static CamelKIntegration integration(String domain) {
        CamelKIntegration.IntegrationSpec.Traits traits = new CamelKIntegration.IntegrationSpec.Traits();
        traits.setEnvironment(new CamelKIntegration.IntegrationSpec.Traits.EnvironmentTrait(
                List.of("BG_VERSION=v1", "CIP_ENGINE_DOMAIN=" + domain)));
        CamelKIntegration.IntegrationSpec spec = new CamelKIntegration.IntegrationSpec();
        spec.setTraits(traits);
        CamelKIntegration integration = new CamelKIntegration();
        integration.setSpec(spec);
        return integration;
    }

    private static KeyResponse key(String name, String session) {
        return KeyResponse.builder().key(LEADERS + name).modifyIndex(42).session(session).build();
    }
}
