package org.qubership.integration.platform.engine.state;

import org.apache.camel.CamelContext;
import org.apache.camel.k.SourceDefinition;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.model.engine.DeploymentInfo;
import org.qubership.integration.platform.engine.model.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.engine.EngineState;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class EngineStateBuilderTest {

    private static final String SNAPSHOT_ID = "23545cc3-5c6d-4cd0-a78b-e304d7ce736e";
    private static final String CHAIN_ID = "d78af111-3f8f-4ca6-823a-67d7738d63f0";
    private static final String CHAIN_NAME = "Test chain";
    private static final String SNAPSHOT_NAME = "V8";
    public static final String DEPLOYMENT_ID = "ab181758-32d9-4743-9201-ff63f48ad452";

    @Mock
    private EngineInfo engineInfo;

    @Mock
    private SourceLoadStateTracker sourceLoadStateTracker;

    @Mock
    private CamelContext camelContext;

    @Mock
    private Registry registry;

    private EngineStateBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new EngineStateBuilder();
        builder.engineInfo = engineInfo;
        builder.sourceLoadStateTracker = sourceLoadStateTracker;

        when(camelContext.getRegistry()).thenReturn(registry);
    }

    @Test
    void shouldBuildEngineStateWithEngineInfoAndDeploymentFromRegistry() {
        SourceDefinition sourceDefinition = sourceDefinitionWithIdOnly();
        SourceLoadStateTracker.SourceLoadState loadState = loadState(
            SourceLoadStateTracker.SourceLoadStage.SUCCESS,
            null
        );
        org.qubership.integration.platform.engine.metadata.DeploymentInfo metadataDeploymentInfo =
            metadataDeploymentInfo();

        when(sourceLoadStateTracker.getSourceDefinitions()).thenReturn(Set.of(sourceDefinition));
        when(sourceLoadStateTracker.getLoadState(SNAPSHOT_ID)).thenReturn(loadState);
        when(registry.findByType(org.qubership.integration.platform.engine.metadata.DeploymentInfo.class))
            .thenReturn(Set.of(metadataDeploymentInfo));

        EngineState engineState = builder.build(camelContext);

        assertSame(engineInfo, engineState.getEngine());
        assertEquals(1, engineState.getDeployments().size());

        EngineDeployment deployment = engineState.getDeployments().get("ab181758-32d9-4743-9201-ff63f48ad452");
        assertEquals(DeploymentStatus.DEPLOYED, deployment.getStatus());
        assertNull(deployment.getErrorMessage());

        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        assertEquals("ab181758-32d9-4743-9201-ff63f48ad452", deploymentInfo.getDeploymentId());
        assertEquals(CHAIN_ID, deploymentInfo.getChainId());
        assertEquals(CHAIN_NAME, deploymentInfo.getChainName());
        assertEquals(SNAPSHOT_ID, deploymentInfo.getSnapshotId());
        assertEquals(SNAPSHOT_NAME, deploymentInfo.getSnapshotName());
        assertNull(deploymentInfo.getChainStatusCode());
    }

    @Test
    void shouldBuildFallbackDeploymentInfoWhenDeploymentInfoIsMissingInRegistry() {
        SourceDefinition sourceDefinition = sourceDefinitionWithIdAndName(SNAPSHOT_ID, CHAIN_NAME);
        SourceLoadStateTracker.SourceLoadState loadState = loadState(
            SourceLoadStateTracker.SourceLoadStage.UNKNOWN,
            null
        );

        when(engineInfo.getDomain()).thenReturn("default");
        when(sourceLoadStateTracker.getSourceDefinitions()).thenReturn(Set.of(sourceDefinition));
        when(sourceLoadStateTracker.getLoadState(SNAPSHOT_ID)).thenReturn(loadState);
        when(registry.findByType(org.qubership.integration.platform.engine.metadata.DeploymentInfo.class))
            .thenReturn(Set.of());

        EngineState engineState = builder.build(camelContext);

        EngineDeployment deployment = engineState.getDeployments().get("default-" + SNAPSHOT_ID);
        assertEquals(DeploymentStatus.PROCESSING, deployment.getStatus());
        assertNull(deployment.getErrorMessage());

        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        assertEquals("default-" + SNAPSHOT_ID, deploymentInfo.getDeploymentId());
        assertNull(deploymentInfo.getChainId());
        assertEquals(CHAIN_NAME, deploymentInfo.getChainName());
        assertEquals(SNAPSHOT_ID, deploymentInfo.getSnapshotId());
        assertNull(deploymentInfo.getSnapshotName());
        assertNull(deploymentInfo.getChainStatusCode());
    }

    @ParameterizedTest
    @MethodSource("deploymentStatuses")
    void shouldBuildDeploymentStatusFromSourceLoadStage(
        SourceLoadStateTracker.SourceLoadStage sourceLoadStage,
        DeploymentStatus expectedStatus
    ) {
        SourceDefinition sourceDefinition = sourceDefinitionWithIdAndName(SNAPSHOT_ID, CHAIN_NAME);
        SourceLoadStateTracker.SourceLoadState loadState = loadState(sourceLoadStage, null);

        when(engineInfo.getDomain()).thenReturn("default");
        when(sourceLoadStateTracker.getSourceDefinitions()).thenReturn(Set.of(sourceDefinition));
        when(sourceLoadStateTracker.getLoadState(SNAPSHOT_ID)).thenReturn(loadState);
        when(registry.findByType(org.qubership.integration.platform.engine.metadata.DeploymentInfo.class))
            .thenReturn(Set.of());

        EngineState engineState = builder.build(camelContext);

        EngineDeployment deployment = engineState.getDeployments().get("default-" + SNAPSHOT_ID);
        assertEquals(expectedStatus, deployment.getStatus());
    }

    @Test
    void shouldSetErrorMessageAndUnexpectedDeploymentErrorCodeWhenSourceLoadFailed() {
        Exception exception = new RuntimeException("Route loading failed");
        SourceDefinition sourceDefinition = sourceDefinitionWithIdAndName(SNAPSHOT_ID, CHAIN_NAME);
        SourceLoadStateTracker.SourceLoadState loadState = loadState(
            SourceLoadStateTracker.SourceLoadStage.FAILED,
            exception
        );

        when(engineInfo.getDomain()).thenReturn("default");
        when(sourceLoadStateTracker.getSourceDefinitions()).thenReturn(Set.of(sourceDefinition));
        when(sourceLoadStateTracker.getLoadState(SNAPSHOT_ID)).thenReturn(loadState);
        when(registry.findByType(org.qubership.integration.platform.engine.metadata.DeploymentInfo.class))
            .thenReturn(Set.of());

        EngineState engineState = builder.build(camelContext);

        EngineDeployment deployment = engineState.getDeployments().get("default-" + SNAPSHOT_ID);
        assertEquals(DeploymentStatus.FAILED, deployment.getStatus());
        assertEquals("Route loading failed", deployment.getErrorMessage());
        assertEquals(
            ErrorCode.UNEXPECTED_DEPLOYMENT_ERROR.getCode(),
            deployment.getDeploymentInfo().getChainStatusCode()
        );
    }

    @Test
    void shouldBuildDeploymentsForAllSourceDefinitions() {
        SourceDefinition firstSource = sourceDefinitionWithIdAndName("first-snapshot", "First chain");
        SourceDefinition secondSource = sourceDefinitionWithIdAndName("second-snapshot", "Second chain");

        when(engineInfo.getDomain()).thenReturn("default");
        when(sourceLoadStateTracker.getSourceDefinitions()).thenReturn(Set.of(firstSource, secondSource));
        when(sourceLoadStateTracker.getLoadState("first-snapshot")).thenReturn(loadState(
            SourceLoadStateTracker.SourceLoadStage.SUCCESS,
            null
        ));
        when(sourceLoadStateTracker.getLoadState("second-snapshot")).thenReturn(loadState(
            SourceLoadStateTracker.SourceLoadStage.PROCESSING,
            null
        ));
        when(registry.findByType(org.qubership.integration.platform.engine.metadata.DeploymentInfo.class))
            .thenReturn(Set.of());

        EngineState engineState = builder.build(camelContext);

        Map<String, EngineDeployment> deployments = engineState.getDeployments();
        assertEquals(2, deployments.size());
        assertEquals(DeploymentStatus.DEPLOYED, deployments.get("default-first-snapshot").getStatus());
        assertEquals(DeploymentStatus.PROCESSING, deployments.get("default-second-snapshot").getStatus());
    }

    private static Stream<Arguments> deploymentStatuses() {
        return Stream.of(
            Arguments.of(SourceLoadStateTracker.SourceLoadStage.UNKNOWN, DeploymentStatus.PROCESSING),
            Arguments.of(SourceLoadStateTracker.SourceLoadStage.PROCESSING, DeploymentStatus.PROCESSING),
            Arguments.of(SourceLoadStateTracker.SourceLoadStage.FAILED, DeploymentStatus.FAILED),
            Arguments.of(SourceLoadStateTracker.SourceLoadStage.SUCCESS, DeploymentStatus.DEPLOYED)
        );
    }

    private static SourceLoadStateTracker.SourceLoadState loadState(
        SourceLoadStateTracker.SourceLoadStage stage,
        Exception exception
    ) {
        return new SourceLoadStateTracker.SourceLoadState(stage, exception);
    }

    private static org.qubership.integration.platform.engine.metadata.DeploymentInfo metadataDeploymentInfo(
        ) {
        return org.qubership.integration.platform.engine.metadata.DeploymentInfo.builder()
            .id(EngineStateBuilderTest.DEPLOYMENT_ID)
            .chain(ChainInfo.builder()
                .id(CHAIN_ID)
                .name(CHAIN_NAME)
                .build())
            .snapshot(SnapshotInfo.builder()
                .id(EngineStateBuilderTest.SNAPSHOT_ID)
                .name(SNAPSHOT_NAME)
                .build())
            .build();
    }

    private static SourceDefinition sourceDefinitionWithIdOnly() {
        SourceDefinition sourceDefinition = mock(SourceDefinition.class);
        when(sourceDefinition.getId()).thenReturn(EngineStateBuilderTest.SNAPSHOT_ID);
        return sourceDefinition;
    }

    private static SourceDefinition sourceDefinitionWithIdAndName(String id, String name) {
        SourceDefinition sourceDefinition = mock(SourceDefinition.class);
        when(sourceDefinition.getId()).thenReturn(id);
        when(sourceDefinition.getName()).thenReturn(name);
        return sourceDefinition;
    }
}
