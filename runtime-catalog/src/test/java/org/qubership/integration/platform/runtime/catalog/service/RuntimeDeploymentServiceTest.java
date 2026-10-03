package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.DeploymentStatus;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.EngineState;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.DeploymentRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RuntimeDeploymentServiceTest {

    private static final String CHAIN_ID = "chain-id";

    @Mock
    private TransactionHandler transactionHandler;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentMapper deploymentMapper;

    @InjectMocks
    private RuntimeDeploymentService runtimeDeploymentService;

    @Test
    void getMicroEngineDeploymentsMapsCreationMetadata() {
        EngineState engineState = EngineState.builder()
                .engine(EngineInfo.builder()
                        .host("engine-host")
                        .domain("custom-domain")
                        .domainType(DomainType.MICRO)
                        .engineDeploymentName("micro-engine")
                        .build())
                .deployments(Map.of(
                        "deployment-with-creator", deployment("deployment-with-creator", 100L, "test-user"),
                        "legacy-deployment", deployment("legacy-deployment", 200L, null)))
                .build();
        runtimeDeploymentService.provideEnginesStateUpdate(List.of(engineState));

        Collection<DeploymentResponse> result = runtimeDeploymentService.getMicroEngineDeployments(CHAIN_ID);

        assertThat(result)
                .filteredOn(deployment -> deployment.getId().equals("deployment-with-creator"))
                .singleElement()
                .satisfies(deployment -> {
                    assertThat(deployment.getCreatedWhen()).isEqualTo(100L);
                    assertThat(deployment.getCreatedBy()).isNotNull();
                    assertThat(deployment.getCreatedBy().getUsername()).isEqualTo("test-user");
                });
        assertThat(result)
                .filteredOn(deployment -> deployment.getId().equals("legacy-deployment"))
                .singleElement()
                .satisfies(deployment -> {
                    assertThat(deployment.getCreatedWhen()).isEqualTo(200L);
                    assertThat(deployment.getCreatedBy()).isNull();
                });
    }

    private EngineDeployment deployment(String deploymentId, Long createdWhen, String createdBy) {
        return EngineDeployment.builder()
                .deploymentInfo(DeploymentInfo.builder()
                        .deploymentId(deploymentId)
                        .chainId(CHAIN_ID)
                        .createdWhen(createdWhen)
                        .createdBy(createdBy)
                        .build())
                .status(DeploymentStatus.DEPLOYED)
                .build();
    }
}
