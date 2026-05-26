package org.qubership.integration.platform.runtime.catalog.service.domains;

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.util.EngineDomainUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;

@Component
@ConditionalOnProperty(prefix = "qip.deploy.micro", name = "enabled", havingValue = "true")
@Profile("!development")
public class MicroDomainSource implements EngineDomainSource {
    private final KubeOperator operator;
    private final EngineDomainUtils domainUtils;

    @Autowired
    public MicroDomainSource(KubeOperator operator, EngineDomainUtils domainUtils) {
        this.operator = operator;
        this.domainUtils = domainUtils;
    }

    @Override
    public List<EngineDomain> getDomains() throws KubeApiException {
        return operator.getDeploymentsByLabel(CAMEL_K_INTEGRATION_LABEL).stream()
                .map(deployment -> EngineDomain.builder()
                        .id(deployment.getId())
                        .name(domainUtils.getDomainName(deployment))
                        .version(deployment.getVersion())
                        .replicas(deployment.getReplicas())
                        .namespace(deployment.getNamespace())
                        .type(DomainType.MICRO)
                        .build())
                .toList();
    }

    @Override
    public List<KubePod> getDomainPods(String domainName) throws KubeApiException {
        return operator.getPodsByLabel(domainUtils.getDomainLabel(), domainName);
    }
}
