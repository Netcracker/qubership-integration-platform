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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.model.MultiConsumer;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.model.domains.EngineDomain;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.EventActionType;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.kubernetes.operator.KubePod;
import org.qubership.integration.platform.runtime.catalog.util.DevModeUtil;
import org.qubership.integration.platform.runtime.catalog.util.EngineDomainUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;


@Slf4j
@Component
public class EngineService {
    private static final String ENGINE_NAME_LABEL = "name";

    private final KubeOperator operator;
    private final DeploymentService deploymentService;
    private final DevModeUtil devModeUtil;
    private final EngineDomainUtils domainUtils;
    // <id, pod, domainName, actionType, userId>
    private final Map<String, MultiConsumer.Consumer5<String, KubePod, String, EventActionType, String>> enginesCallbacks = new ConcurrentHashMap<>();

    @Value("${qip.engine.app-check-custom-label}")
    private String engineAppCheckLabel;

    @Value("${qip.domain.default}")
    private String engineDefaultDomain;

    @Value("${kubernetes.cluster.namespace}")
    private String namespace;

    @Autowired
    public EngineService(KubeOperator operator,
                         DeploymentService deploymentService,
                         DevModeUtil devModeUtil,
                         EngineDomainUtils domainUtils) {
        this.operator = operator;
        this.deploymentService = deploymentService;
        this.devModeUtil = devModeUtil;
        this.domainUtils = domainUtils;
    }

    /**
     * @return deployment with domain name (engine prefix and version suffix are deleted!)
     * @throws KubeApiException
     */
    public List<EngineDomain> getDomains() throws KubeApiException {
        if (isDevMode()) {
            return Collections.singletonList(EngineDomain.builder()
                    .id(engineDefaultDomain)
                    .name(engineDefaultDomain)
                    .replicas(1)
                    .namespace(namespace)
                    .type(DomainType.NATIVE)
                    .build()
            );
        }
        List<KubeDeployment> deployments = getDeployments();
        return deployments.stream()
                .map(deployment -> EngineDomain.builder()
                        .id(deployment.getId())
                        .name(domainUtils.convertKubeDeploymentToDomainName(deployment.getName()))
                        .version(deployment.getVersion())
                        .replicas(deployment.getReplicas())
                        .namespace(deployment.getNamespace())
                        .type(deployment.getLabels().containsKey(CAMEL_K_INTEGRATION_LABEL) ? DomainType.MICRO : DomainType.NATIVE)
                        .build())
                .toList();
    }

    public EngineDomain getDomainByName(String domainName) {
        return getDomains().stream().filter(domain -> domain.getName().equals(domainName)).findFirst().orElse(null);
    }

    /**
     * @return deployment as is
     * @throws KubeApiException
     */
    private List<KubeDeployment> getDeployments() throws KubeApiException {
        List<KubeDeployment> deployments = new ArrayList<>();
        deployments.addAll(operator.getDeploymentsByLabel(engineAppCheckLabel, "true"));
        deployments.addAll(operator.getDeploymentsByLabel(CAMEL_K_INTEGRATION_LABEL));
        return deployments;
    }

    public List<KubePod> getEnginesPods(String domainName) throws KubeApiException {
        List<KubePod> pods = new ArrayList<>();
        pods.addAll(operator.getPodsByLabel(ENGINE_NAME_LABEL, getActiveKubeDeploymentNameByDomain(domainName)));
        pods.addAll(operator.getPodsByLabel(CAMEL_K_INTEGRATION_LABEL, domainName));
        return pods;
    }

    public boolean isDevMode() {
        return devModeUtil.isDevMode();
    }

    public String subscribeEngines(MultiConsumer.Consumer5<String, KubePod, String, EventActionType, String> callback) {
        String id = UUID.randomUUID().toString();
        enginesCallbacks.put(id, callback);
        return id;
    }

    public long deploymentsCountByDomain(String domainName) {
        return deploymentService.getDeploymentsCountByDomain(domainName);
    }

    public String getActiveKubeDeploymentNameByDomain(String domainName) {
        for (KubeDeployment deployment : getDeployments()) {
            if (domainUtils.convertKubeDeploymentToDomainName(deployment.getName()).equals(domainName)) {
                return deployment.getName();
            }
        }
        return null;
    }
}
