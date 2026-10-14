package org.qubership.integration.platform.engine.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.metadata.ChainInfo;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;

import java.util.Objects;

import static java.util.Objects.isNull;

@ApplicationScoped
public class ChainDeploymentChecker {

    @Inject
    CamelContext camelContext;

    public boolean isChainDeployed(String chainId) {
        if (isNull(chainId)) {
            return false;
        }
        return camelContext.getRegistry()
                .findByType(DeploymentInfo.class)
                .stream()
                .map(DeploymentInfo::getChain)
                .filter(Objects::nonNull)
                .map(ChainInfo::getId)
                .anyMatch(chainId::equals);
    }
}
