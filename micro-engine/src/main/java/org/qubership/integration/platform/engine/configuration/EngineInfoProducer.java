package org.qubership.integration.platform.engine.configuration;

import jakarta.enterprise.inject.Produces;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
public class EngineInfoProducer {
    @Produces
    public EngineInfo getEngineInfo(ApplicationConfiguration applicationConfiguration) {
        return EngineInfo.builder()
                .domain(getDomain(applicationConfiguration))
                .engineDeploymentName(applicationConfiguration.getDeploymentName())
                .host(getCurrentHost())
                .build();
    }

    private String getDomain(ApplicationConfiguration applicationConfiguration) {
        boolean isDefault = applicationConfiguration.getDefaultEngineMicroserviceName()
                .equals(applicationConfiguration.getMicroserviceName());

            return isDefault
                    ? applicationConfiguration.getEngineDefaultDomain()
                    : applicationConfiguration.getMicroserviceName();
    }

    private String getCurrentHost() {
        try {
            var localHost = InetAddress.getLocalHost();
            return localHost.getHostAddress();
        } catch (UnknownHostException e) {
            log.error("Can't identify current host address");
        }
        return "";
    }
}
