package org.qubership.integration.platform.runtime.catalog.service.domains;

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Profile("development")
@ConditionalOnProperty(prefix = "qip.deploy.classic", name = "enabled", havingValue = "true")
public class DevModeDomainSource implements EngineDomainSource {
    @Value("${qip.domain.default}")
    private String engineDefaultDomain;

    @Value("${kubernetes.cluster.namespace}")
    private String namespace;

    @Override
    public List<EngineDomain> getDomains() throws KubeApiException {
        return Collections.singletonList(EngineDomain.builder()
                .id(engineDefaultDomain)
                .name(engineDefaultDomain)
                .replicas(1)
                .namespace(namespace)
                .type(DomainType.CLASSIC)
                .build());
    }

    @Override
    public List<KubePod> getDomainPods(String domainName) throws KubeApiException {
        return List.of();
    }
}
