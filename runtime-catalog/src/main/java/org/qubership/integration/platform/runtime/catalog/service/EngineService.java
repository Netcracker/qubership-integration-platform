/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.model.MultiConsumer;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.EventActionType;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.service.domains.EngineDomainSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class EngineService {
    private final DeploymentService deploymentService;
    // <id, pod, domainName, actionType, userId>
    private final Map<String, MultiConsumer.Consumer5<String, KubePod, String, EventActionType, String>> enginesCallbacks = new ConcurrentHashMap<>();
    private final DomainProperties domainProperties;
    private final List<EngineDomainSource> engineDomainSources;

    @Autowired
    public EngineService(
            DeploymentService deploymentService,
            DomainProperties domainProperties,
            List<EngineDomainSource> engineDomainSources
    ) {
        this.deploymentService = deploymentService;
        this.domainProperties = domainProperties;
        this.engineDomainSources = engineDomainSources;
    }

    /**
     * @return deployment with domain name (engine prefix and version suffix are deleted!)
     * @throws KubeApiException
     */
    public List<EngineDomain> getDomains() throws KubeApiException {
        return engineDomainSources
                .stream()
                .map(EngineDomainSource::getDomains)
                .flatMap(Collection::stream)
                .toList();
    }

    public EngineDomain getDomainByName(String domainName) {
        return getDomains()
                .stream()
                .filter(domain -> domain.getName().equals(domainName))
                .findFirst()
                .orElse(null);
    }

    public List<KubePod> getEnginesPods(String domainName) throws KubeApiException {
        return engineDomainSources
                .stream()
                .map(source -> source.getDomainPods(domainName))
                .flatMap(Collection::stream)
                .toList();
    }

    public String subscribeEngines(MultiConsumer.Consumer5<String, KubePod, String, EventActionType, String> callback) {
        String id = UUID.randomUUID().toString();
        enginesCallbacks.put(id, callback);
        return id;
    }

    public long deploymentsCountByDomain(String domainName) {
        return domainProperties.getClassic().isEnabled()
                ? deploymentService.getDeploymentsCountByDomain(domainName)
                : 0;
    }
}
