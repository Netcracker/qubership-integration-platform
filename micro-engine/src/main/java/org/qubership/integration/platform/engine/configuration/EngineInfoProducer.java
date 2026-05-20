package org.qubership.integration.platform.engine.configuration;

import jakarta.enterprise.inject.Produces;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.model.engine.DomainType;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
public class EngineInfoProducer {
    @ConfigProperty(name = "qip.engine.domain")
    String domain;

    @Produces
    public EngineInfo getEngineInfo(ApplicationConfiguration applicationConfiguration) {
        return EngineInfo.builder()
                .domain(domain)
                .domainType(DomainType.MICRO)
                .engineDeploymentName(applicationConfiguration.getDeploymentName())
                .host(getCurrentHost())
                .build();
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
