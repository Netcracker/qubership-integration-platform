package org.qubership.integration.platform.engine.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.k.SourceDefinition;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.SnapshotInfo;
import org.qubership.integration.platform.engine.model.engine.DeploymentInfo;
import org.qubership.integration.platform.engine.model.engine.DeploymentStatus;
import org.qubership.integration.platform.engine.model.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;
import org.qubership.integration.platform.engine.model.engine.EngineState;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class EngineStateBuilder {
    @Inject
    EngineInfo engineInfo;

    @Inject
    SourceLoadStateTracker sourceLoadStateTracker;

    public EngineState build(CamelContext camelContext) {
        return EngineState.builder()
                .engine(engineInfo)
                .deployments(buildDeployments(camelContext))
                .build();
    }

    private Map<String, EngineDeployment> buildDeployments(CamelContext camelContext) {
        return sourceLoadStateTracker.getSourceDefinitions().stream()
                .map(source -> buildDeployment(
                        camelContext,
                        source,
                        sourceLoadStateTracker.getLoadState(source.getId())))
                .collect(Collectors.toMap(
                        d -> d.getDeploymentInfo().getDeploymentId(),
                        Function.identity()
                ));
    }

    private EngineDeployment buildDeployment(
            CamelContext camelContext,
            SourceDefinition sourceDefinition,
            SourceLoadStateTracker.SourceLoadState sourceLoadState
    ) {
        return EngineDeployment.builder()
                .deploymentInfo(buildDeploymentInfo(camelContext, sourceDefinition, sourceLoadState))
                .status(buildDeploymentStatus(sourceLoadState))
                .errorMessage(Optional.ofNullable(sourceLoadState.exception())
                        .map(Exception::getMessage)
                        .orElse(null))
                .build();
    }

    private DeploymentInfo buildDeploymentInfo(
            CamelContext camelContext,
            SourceDefinition sourceDefinition,
            SourceLoadStateTracker.SourceLoadState sourceLoadState
    ) {
        // Assuming that source ID is a corresponding snapshot ID.
        String snapshotId = sourceDefinition.getId();
        org.qubership.integration.platform.engine.metadata.DeploymentInfo deploymentInfo = camelContext.getRegistry()
                .findByType(org.qubership.integration.platform.engine.metadata.DeploymentInfo.class)
                .stream()
                // Assuming that source ID is a corresponding snapshot ID.
                .filter(info -> snapshotId.equals(info.getSnapshot().getId()))
                .findAny()
                .orElse(org.qubership.integration.platform.engine.metadata.DeploymentInfo.builder()
                        .id(String.format("%s-%s", engineInfo.getDomain(), snapshotId))
                        .chain(ChainInfo.builder()
                                // Assuming that source name is a corresponding chain name.
                                .name(sourceDefinition.getName())
                                .build())
                        .snapshot(SnapshotInfo.builder()
                                .id(snapshotId)
                                .build())
                        .build());
        return DeploymentInfo.builder()
                .deploymentId(deploymentInfo.getId())
                .chainId(deploymentInfo.getChain().getId())
                .chainName(deploymentInfo.getChain().getName())
                .snapshotId(deploymentInfo.getSnapshot().getId())
                .snapshotName(deploymentInfo.getSnapshot().getName())
                .chainStatusCode(
                        SourceLoadStateTracker.SourceLoadStage.FAILED
                                .equals(sourceLoadState.stage())
                                ? ErrorCode.UNEXPECTED_DEPLOYMENT_ERROR.getCode()
                                : null
                )
                .build();
    }

    private DeploymentStatus buildDeploymentStatus(SourceLoadStateTracker.SourceLoadState sourceLoadState) {
        return switch (sourceLoadState.stage()) {
            case UNKNOWN, PROCESSING -> DeploymentStatus.PROCESSING;
            case FAILED -> DeploymentStatus.FAILED;
            case SUCCESS -> DeploymentStatus.DEPLOYED;
        };
    }
}
