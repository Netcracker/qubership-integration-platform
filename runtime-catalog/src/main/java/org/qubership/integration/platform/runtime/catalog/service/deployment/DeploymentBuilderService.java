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

package org.qubership.integration.platform.runtime.catalog.service.deployment;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.builder.BuilderConstants;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.catalog.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.catalog.model.library.ElementType;
import org.qubership.integration.platform.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.MaskedField;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.*;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilderFactory;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.catalog.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.catalog.consul.ConfigurationPropertiesConstants.*;
import static org.qubership.integration.platform.catalog.model.constant.CamelNames.*;

@Slf4j
@Component
@Transactional
public class DeploymentBuilderService {
    @Deprecated(since = "23.4")
    private static final Pattern RANDOM_ID_PLACEHOLDER_PATTERN = Pattern.compile("%%\\{random-id-placeholder}");
    private static final Pattern DEPLOYMENT_ID_PLACEHOLDER_PATTERN = Pattern.compile("%%\\{deployment-id-placeholder}");
    private static final String DOMAIN_PLACEHOLDER = "%%{domain-placeholder}";

    private final ChainService chainService;
    private final SnapshotService snapshotService;
    private final ElementUtils elementUtils;
    private final ElementPropertiesBuilderFactory elementPropertiesBuilderFactory;
    private final LibraryElementsService libraryService;
    private final DeploymentRouteMapper deploymentRouteMapper;
    private final SystemService systemService;
    private final EnvironmentService environmentService;

    @Autowired
    public DeploymentBuilderService(
            @Lazy ChainService chainService,
            @Lazy SnapshotService snapshotService,
            ElementUtils elementUtils,
            ElementPropertiesBuilderFactory elementPropertiesBuilderFactory,
            LibraryElementsService libraryService,
            DeploymentRouteMapper deploymentRouteMapper,
            SystemService systemService,
            EnvironmentService environmentService) {
        this.chainService = chainService;
        this.snapshotService = snapshotService;
        this.elementUtils = elementUtils;
        this.elementPropertiesBuilderFactory = elementPropertiesBuilderFactory;
        this.libraryService = libraryService;
        this.deploymentRouteMapper = deploymentRouteMapper;
        this.systemService = systemService;
        this.environmentService = environmentService;
    }

    public List<DeploymentUpdate> buildDeploymentsUpdate(List<Deployment> deployments) {
        List<DeploymentUpdate> result = new ArrayList<>();
        for (Deployment deployment : deployments) {
            Chain chain = chainService.findById(deployment.getChain().getId());
            Snapshot snapshot = snapshotService.findById(deployment.getSnapshot().getId());

            DeploymentConfiguration config = createUpdateDeploymentConfiguration(deployment);

            result.add(DeploymentUpdate.builder()
                    .deploymentInfo(DeploymentInfo.builder()
                            .deploymentId(deployment.getId())
                            .chainId(chain.getId())
                            .chainName(chain.getName())
                            .snapshotName(snapshot.getName())
                            .snapshotId(snapshot.getId())
                            .createdWhen(deployment.getCreatedWhen().getTime())
                            .containsCheckpointElements(containsCheckpointsElements(config.getProperties()))
                            .containsSchedulerElements(containsSchedulerElements(config.getProperties()))
                            .build())
                    .maskedFields(chain.getMaskedFields().stream()
                                    .map(MaskedField::getName)
                                    .collect(Collectors.toSet()))
                    .configuration(config)
                    .build());
        }
        return result;
    }

    public List<DeploymentUpdate> buildDeploymentsStop(List<DeploymentInfo> deployments) {
        List<DeploymentUpdate> result = new ArrayList<>();
        for (DeploymentInfo info : deployments) {
            result.add(DeploymentUpdate.builder()
                    .deploymentInfo(info)
                    .build());
        }
        return result;
    }

    private DeploymentConfiguration createUpdateDeploymentConfiguration(Deployment deployment) {
        Snapshot snapshot = deployment.getSnapshot();

        Set<ChainElement> groupContainers = snapshot.getElements().stream()
                .filter(item -> ElementService.CONTAINER_TYPE_NAME.equals(item.getType()) ||
                        Optional.ofNullable(libraryService.getElementDescriptor(item.getType()))
                                .map(descriptor -> ElementType.REUSE == descriptor.getType())
                                .orElse(false))
                .collect(Collectors.toSet());

        List<ChainElement> filteredElements = snapshot.getElements().stream()
                .filter(item -> !item.getType().equals(ElementService.CONTAINER_TYPE_NAME) &&
                        Optional.ofNullable(libraryService.getElementDescriptor(item.getType()))
                                .map(ElementDescriptor::getType)
                                .map(type -> ElementType.REUSE != type
                                        && ElementType.REUSE_REFERENCE != type
                                        && ElementType.SWIMLANE != type)
                                .orElse(true))
                .collect(Collectors.toList());
        filteredElements = elementUtils.splitCompositeTriggers(filteredElements);

        List<ElementProperties> elementProperties = new ArrayList<>();
        filteredElements.stream()
                .map(element -> {
                    Map<String, String> properties = new HashMap<>(elementPropertiesBuilderFactory
                            .getElementPropertiesBuilder(element).build(element));
                    if (element.getParent() != null) {
                        if (!groupContainers.contains(element.getParent())) {
                            properties.put(PARENT_ELEMENT_ID, element.getParent().getId());
                            properties.put(PARENT_ELEMENT_ORIGINAL_ID, element.getParent().getOriginalId());
                            properties.put(PARENT_ELEMENT_NAME, element.getParent().getName());
                            if (ELEMENTS_WITH_INTERMEDIATE_CHILDREN.contains(element.getParent().getType())) {
                                properties.put(HAS_INTERMEDIATE_PARENTS, Boolean.TRUE.toString());
                            }
                        }
                        if (BuilderConstants.REUSE_ELEMENT_TYPE.equals(element.getParent().getType())) {
                            properties.put(REUSE_ORIGINAL_ID, element.getParent().getOriginalId());
                        }
                    }
                    if (SERVICE_CALL_ELEMENT.equals(element.getType())) {
                        if (IntegrationSystemType.EXTERNAL.name().equals(element.getProperty(CamelOptions.SYSTEM_TYPE))) {
                            String systemId = (String) element.getProperty(CamelOptions.SYSTEM_ID);
                            if (StringUtils.isNotEmpty(systemId)) {
                                IntegrationSystem system = systemService.findById(systemId);
                                properties.put(EXTERNAL_SERVICE_NAME, system.getName());
                                String activeEnvironmentId = system.getActiveEnvironmentId();
                                if (StringUtils.isNotEmpty(activeEnvironmentId)) {
                                    Environment env = environmentService.getByIdForSystem(systemId, activeEnvironmentId);
                                    properties.put(EXTERNAL_SERVICE_ENV_NAME, env.getName());
                                }
                            }
                        }
                    }
                    return ElementProperties.builder().elementId(element.getId()).properties(properties).build();
                })
                .forEach(elementProperties::add);

        String xml = replacePlaceholders(snapshot, deployment);
        return DeploymentConfiguration.builder()
                .xml(xml)
                .properties(elementProperties)
                .routes(deploymentRouteMapper.asUpdates(deployment.getDeploymentRoutes()))
                .build();
    }

    private boolean containsCheckpointsElements(List<ElementProperties> elementProperties) {
        return elementProperties.stream().anyMatch(
                properties -> properties.getProperties() != null &&
                        CHECKPOINT.equals(properties.getProperties().get(ELEMENT_TYPE)));
    }

    private boolean containsSchedulerElements(List<ElementProperties> elementProperties) {
        return elementProperties.stream().anyMatch(
                properties -> properties.getProperties() != null &&
                        SCHEDULER.equals(properties.getProperties().get(ELEMENT_TYPE)));
    }

    private String replacePlaceholders(Snapshot snapshot, Deployment deployment) {
        StringBuilder result = replacePlaceholder(
                snapshot.getXmlDefinition(),
                UUID.randomUUID().toString(),
                RANDOM_ID_PLACEHOLDER_PATTERN); // TODO deprecated and must not be used!
        result = replacePlaceholder(result.toString(), deployment.getId(), DEPLOYMENT_ID_PLACEHOLDER_PATTERN);
        return result.toString().replace(DOMAIN_PLACEHOLDER, deployment.getDomain().toLowerCase());
    }

    @NotNull
    private static StringBuilder replacePlaceholder(String input, String replacement, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        return result;
    }
}
