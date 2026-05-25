package org.qubership.integration.platform.runtime.catalog.service.domains;

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.util.EngineDomainUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;

@Component
@ConditionalOnProperty(prefix = "qip.deploy.classic", name = "enabled", havingValue = "true")
@Profile("!development")
public class ClassicDomainSource implements EngineDomainSource {
    private static final String ENGINE_NAME_LABEL = "name";

    @Value("${qip.engine.app-check-custom-label}")
    private String engineAppCheckLabel;

    private final KubeOperator operator;
    private final EngineDomainUtils domainUtils;

    @Autowired
    public ClassicDomainSource(KubeOperator operator, EngineDomainUtils domainUtils) {
        this.operator = operator;
        this.domainUtils = domainUtils;
    }

    @Override
    public List<EngineDomain> getDomains() throws KubeApiException {
        return getDeployments()
                .stream()
                .map(deployment -> EngineDomain.builder()
                        .id(deployment.getId())
                        .name(domainUtils.getDomainName(deployment))
                        .version(deployment.getVersion())
                        .replicas(deployment.getReplicas())
                        .namespace(deployment.getNamespace())
                        .type(DomainType.CLASSIC)
                        .build())
                .toList();
    }

    @Override
    public List<KubePod> getDomainPods(String domainName) throws KubeApiException {
        String name = getActiveKubeDeploymentNameByDomain(domainName);
        return isNull(name)
                ? Collections.emptyList()
                : operator.getPodsByLabel(ENGINE_NAME_LABEL, name);
    }

    private List<KubeDeployment> getDeployments() throws KubeApiException {
        return operator.getDeploymentsByLabel(engineAppCheckLabel, "true");
    }

    private String getActiveKubeDeploymentNameByDomain(String domainName) {
        for (KubeDeployment deployment : getDeployments()) {
            if (domainUtils.getDomainName(deployment).equals(domainName)) {
                return deployment.getName();
            }
        }
        return null;
    }
}
