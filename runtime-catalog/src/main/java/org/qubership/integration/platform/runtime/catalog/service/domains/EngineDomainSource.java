package org.qubership.integration.platform.runtime.catalog.service.domains;

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;

import java.util.List;

public interface EngineDomainSource {
    List<EngineDomain> getDomains() throws KubeApiException;

    List<KubePod> getDomainPods(String domainName) throws KubeApiException;
}
