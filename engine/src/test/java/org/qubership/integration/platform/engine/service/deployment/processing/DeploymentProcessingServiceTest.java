package org.qubership.integration.platform.engine.service.deployment.processing;

import org.apache.camel.spring.SpringCamelContext;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeploymentProcessingServiceTest {

    @Test
    void processAfterContextStartedRunsOnlyTheAfterStartActions() {
        DeploymentProcessingAction before = mock(DeploymentProcessingAction.class);
        DeploymentProcessingAction created = mock(DeploymentProcessingAction.class);
        DeploymentProcessingAction started = mock(DeploymentProcessingAction.class);
        DeploymentProcessingAction stop = mock(DeploymentProcessingAction.class);
        DeploymentProcessingService service = new DeploymentProcessingService(
                List.of(before), List.of(created), List.of(started), List.of(stop));
        SpringCamelContext context = mock(SpringCamelContext.class);
        DeploymentInfo deploymentInfo = DeploymentInfo.builder().deploymentId("d1").chainId("c1").build();
        DeploymentConfiguration configuration = DeploymentConfiguration.builder().build();

        service.processAfterContextStarted(context, deploymentInfo, configuration);

        verify(started).execute(context, deploymentInfo, configuration);
        verifyNoInteractions(before, created, stop);
    }
}
