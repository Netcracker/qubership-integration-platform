package org.qubership.integration.platform.engine.service.debugger;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.consul.ConsulService;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.logging.SessionsLoggingLevel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CamelDebuggerPropertiesServiceTest {

    private static final String CHAIN_ID = "chain";
    private static final String DEPLOYMENT_ID = "deployment";

    private final ConsulService consul = mock(ConsulService.class);
    private final CamelDebuggerPropertiesService service = new CamelDebuggerPropertiesService(consul);

    @BeforeEach
    void registerDeployment() {
        service.mergeWithRuntimeProperties(CamelDebuggerProperties.builder()
            .deploymentInfo(DeploymentInfo.builder().chainId(CHAIN_ID).deploymentId(DEPLOYMENT_ID).build())
            .build());
    }

    @Test
    void discardsWatchSnapshotWhenDeploymentRefreshedTheCache() {
        DeploymentRuntimeProperties deployed = properties(SessionsLoggingLevel.DEBUG);
        DeploymentRuntimeProperties later = properties(SessionsLoggingLevel.INFO);
        when(consul.readChainRuntimeConfig()).thenReturn(Map.of(CHAIN_ID, deployed));
        when(consul.waitForChainRuntimeConfig())
            .thenAnswer(invocation -> {
                service.refreshRuntimePropertiesForDeployment();
                return Pair.of(true, Map.of(CHAIN_ID, properties(SessionsLoggingLevel.OFF)));
            })
            .thenReturn(Pair.of(true, Map.of(CHAIN_ID, later)));

        service.watchRuntimeProperties();

        assertSame(deployed, service.getActualProperties(DEPLOYMENT_ID).getActualRuntimeProperties());
        verify(consul).rollbackChainsRuntimeConfigLastIndex();

        service.watchRuntimeProperties();

        assertSame(later, service.getActualProperties(DEPLOYMENT_ID).getActualRuntimeProperties());
    }

    @Test
    void keepsCachedSettingsWhenDeploymentRefreshFails() {
        DeploymentRuntimeProperties cached = properties(SessionsLoggingLevel.DEBUG);
        when(consul.waitForChainRuntimeConfig()).thenReturn(Pair.of(true, Map.of(CHAIN_ID, cached)));
        when(consul.readChainRuntimeConfig()).thenThrow(new IllegalStateException("Consul unavailable"));
        service.watchRuntimeProperties();

        service.refreshRuntimePropertiesForDeployment();

        assertSame(cached, service.getActualProperties(DEPLOYMENT_ID).getActualRuntimeProperties());
    }

    private static DeploymentRuntimeProperties properties(SessionsLoggingLevel level) {
        return DeploymentRuntimeProperties.builder().sessionsLoggingLevel(level).build();
    }
}
