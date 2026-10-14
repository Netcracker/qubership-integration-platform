package org.qubership.integration.platform.engine.service;

import lombok.RequiredArgsConstructor;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static java.util.Objects.isNull;

@Component
@RequiredArgsConstructor
public class ChainDeploymentChecker {

    private final IntegrationRuntimeService integrationRuntimeService;

    public boolean isChainDeployed(String chainId) {
        if (isNull(chainId)) {
            return false;
        }
        return integrationRuntimeService.getCache().getDeployments().values().stream()
                .map(EngineDeployment::getDeploymentInfo)
                .filter(Objects::nonNull)
                .map(DeploymentInfo::getChainId)
                .anyMatch(chainId::equals);
    }
}
