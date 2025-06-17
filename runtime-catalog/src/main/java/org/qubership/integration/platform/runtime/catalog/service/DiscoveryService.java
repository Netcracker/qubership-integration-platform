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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.consul.ContextHeaders;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationDiscoveryException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeService;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveredServiceDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveryErrorDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveryResultDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DiscoveryServiceMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.SpecificationImportService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.swagger.v3.parser.util.DeserializationUtils.isJson;
import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType.MAAS_BY_CLASSIFIER;
import static org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType.MANUAL;
import static org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType.INTERNAL;
import static org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource.DISCOVERED;

@Service
@Slf4j
public class DiscoveryService {
    private final KubeOperator operator;
    private final SystemService systemService;
    private final EnvironmentService environmentService;
    private final SystemModelService systemModelService;
    private final SpecificationImportService specificationImportService;
    private final SpecificationGroupService specificationGroupService;
    private final YAMLMapper yamlMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final DiscoveryServiceMapper discoveryServiceMapper;
    private final ConfigParameterService configParameterService;
    private final ActionsLogService actionLogger;

    @Autowired
    public DiscoveryService(
            KubeOperator operator,
            SystemService systemService,
            EnvironmentService environmentService,
            SystemModelService systemModelService,
            SpecificationImportService specificationImportService,
            SpecificationGroupService specificationGroupService,
            YAMLMapper yamlMapper,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            RestTemplate restTemplateMS,
            DiscoveryServiceMapper discoveryServiceMapper,
            ConfigParameterService configParameterService,
            ActionsLogService actionLogger
    ) {
        this.operator = operator;
        this.systemService = systemService;
        this.environmentService = environmentService;
        this.systemModelService = systemModelService;
        this.specificationImportService = specificationImportService;
        this.specificationGroupService = specificationGroupService;
        this.yamlMapper = yamlMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateMS;
        this.discoveryServiceMapper = discoveryServiceMapper;
        this.configParameterService = configParameterService;
        this.actionLogger = actionLogger;
    }

    private record DiscoveredSpecificationSource(String contentType, String content) {
    }

    @AllArgsConstructor
    @Getter
    private static class SpecificationDiscoveryErrorMsg {
        private String serviceName;
        private String errorMessage;
    }

    @AllArgsConstructor
    @Getter
    private static class SpecificationDiscoveryDTO {
        private String type;
        private String name;
        private String url;
        private String contentType;
        private String content;
        private String version;
        private String sourceFileName;
    }

    @AllArgsConstructor
    @Getter
    private static class SpecificationDiscoveryResult {
        private String serviceAddress;
        private List<SpecificationDiscoveryDTO> specificationDiscoveryDTOS;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    private static class DiscoveryStatusDTO {
        private DiscoveryResultDTO result;
        private String errorMessage;
    }

    @AllArgsConstructor
    @Getter
    private static class SpecificationChanges {
        private List<SpecificationGroup> createdGroups;
        private List<SystemModel> createdSpecifications;
    }

    private static final String DISCOVERY_RESULT_NAME = "discoveryResult";
    private static final String DISCOVERY_PROGRESS_NAME = "discoveryProgress";
    private static final String DISCOVERY_NAMESPACE = "discovery";
    private static final String SPEC_FIELD_NAME = "name";
    private static final String SPEC_FIELD_URL = "url";
    private static final String SPEC_FIELD_URL_LIST = "urls";
    private static final String SPEC_FIELD_ID = "id";
    private static final String SPEC_FIELD_VERSION = "version";
    private static final String SPEC_FIELD_INFO = "info";
    private static final String DISCOVERY_COMPLETE = "100";
    private static final String DISCOVERY_START = "0";
    private static final Integer PRIORITY_SERVICE_PORT = 8080;
    private static final Map<OperationProtocol, String> PROTOCOL_POSTFIX_MAP = Map.of(
            OperationProtocol.HTTP, "",
            OperationProtocol.KAFKA, "async"
    );
    private double percent;
    private final Object lock = new Object();

    public void runDiscovery() {
        synchronized (lock) {
            String requestId = MDC.get(ContextHeaders.REQUEST_ID);
            if (!isDiscoveryComplete()) {
                throw new RuntimeException("Autodiscovery is in progress");
            }

            setDiscoveryStatus(DISCOVERY_START);
            CompletableFuture
                    .supplyAsync(() -> {
                        MDC.put(ContextHeaders.REQUEST_ID, requestId);
                        return runDiscoveryAsync();
                    })
                    .whenCompleteAsync(
                            (DiscoveryResultDTO result, Throwable throwable) -> {
                                MDC.put(ContextHeaders.REQUEST_ID, requestId);
                                discoveryComplete(result, throwable);
                            }
                    );
        }
    }

    private void discoveryComplete(DiscoveryResultDTO result, Throwable throwable) {
        String errorMessage = null;
        if (throwable != null) {
            errorMessage = throwable.getMessage();
        }
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.SERVICE_DISCOVERY)
                .operation(LogOperation.EXECUTE)
                .build());
        setDiscoveryStatusResult(result, errorMessage);
    }

    private DiscoveryResultDTO runDiscoveryAsync() {
        log.info("Discovery started");
        String requestId = MDC.get(ContextHeaders.REQUEST_ID);

        List<CompletableFuture<List<IntegrationSystem>>> newlyDiscoveredFuture = new ArrayList<>();
        List<CompletableFuture<SpecificationChanges>> specificationChangedFuture = new ArrayList<>();
        List<SpecificationDiscoveryErrorMsg> errorMessages = new ArrayList<>();

        List<IntegrationSystem> systems = systemService.getAllDiscoveredServices();
        List<KubeService> services = operator.getServices();
        percent = 99.0 / (services.size() + systems.size());
        log.debug("Percent step size: {}", percent);

        for (IntegrationSystem system : systems) {
            specificationChangedFuture.add(CompletableFuture.supplyAsync(() -> {
                MDC.put(ContextHeaders.REQUEST_ID, requestId);
                return makeSpecificationChange(system, errorMessages, services);
            }));
        }

        for (KubeService service : services) {
            newlyDiscoveredFuture.add(CompletableFuture.supplyAsync(() -> {
                MDC.put(ContextHeaders.REQUEST_ID, requestId);
                return createDiscoveredService(service, errorMessages);
            }));
        }

        return toDiscoveryResultDTO(
                getFuturesResultFlat(newlyDiscoveredFuture),
                getFuturesResult(specificationChangedFuture),
                errorMessages
        );
    }

    private <T> List<T> getFuturesResult(List<CompletableFuture<T>> futureList) {
        return futureList.stream()
                .filter(Objects::nonNull)
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private <T> List<T> getFuturesResultFlat(List<CompletableFuture<List<T>>> futureLists) {
        return getFuturesResult(futureLists).stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    private SpecificationChanges makeSpecificationChange(IntegrationSystem system,
                                                         List<SpecificationDiscoveryErrorMsg> errorMessages,
                                                         List<KubeService> services) {
        List<String> ignoreUrl = Collections.emptyList();
        if (system.getSpecificationGroups() != null) {
            ignoreUrl = system.getSpecificationGroups().stream().filter(group -> !group.isSynchronization())
                    .map(SpecificationGroup::getUrl).filter(Objects::nonNull).collect(Collectors.toList());
        }

        KubeService service = findCorrespondingService(system, services);
        SpecificationDiscoveryResult specificationDiscoveryResult = null;
        if (service != null) {
            specificationDiscoveryResult = runSpecificationDiscovery(service.getName(), service.getPorts(),
                    system.getProtocol(), ignoreUrl);
        }
        if (specificationDiscoveryResult == null) {
            log.debug("Not found specifications for system: {}", system.getName());
            addProgress();
            return null;
        }

        Set<String> oldSystemModelsIds = systemModelService.getSystemModelsBySystemId(system.getId()).stream()
                .map(AbstractEntity::getId).collect(Collectors.toSet());

        List<SpecificationGroup> createdGroups = new ArrayList<>();
        List<SystemModel> createdSpecifications = new ArrayList<>();
        for (SpecificationDiscoveryDTO specificationDTO : specificationDiscoveryResult.getSpecificationDiscoveryDTOS()) {
            SpecificationGroup specificationGroup = specificationGroupService.getById(
                    specificationGroupService.buildSpecificationGroupId(system, specificationDTO.getName()));
            if (specificationGroup == null) {
                specificationGroup = specificationGroupService.getSpecificationGroupByNameAndSystem(
                        specificationDTO.getName(), system);
            }
            if (specificationGroup == null) {
                specificationGroup = specificationGroupService.createAndSaveUniqueSpecificationGroup(
                        system, specificationDTO.getName(), specificationDTO.getType(), specificationDTO.getUrl(), true);
                createdGroups.add(specificationGroup);
            } else if (isNull(specificationGroup.getUrl()) || !specificationGroup.getUrl().equals(specificationDTO.getUrl())) {
                specificationGroup.setUrl(specificationDTO.getUrl());
                specificationGroup.setSynchronization(true);
                specificationGroupService.update(specificationGroup);
            }

            // skip spec if one already exists (by name) in a spec group
            if (systemModelService.getSystemModelByVersionAndSpecificationGroupId(specificationGroup.getId(),
                    specificationDTO.getVersion()) != null) {
                continue;
            }

            try {
                SystemModel model = createSpecification(
                        specificationGroup,
                        specificationDTO,
                        oldSystemModelsIds,
                        msg -> addErrorMessage(errorMessages, system.getInternalServiceName(), specificationDTO.getName(), msg)
                );
                String groupId = specificationGroup.getId();
                if (createdGroups.stream().noneMatch(group -> group.getId().equals(groupId))) {
                    createdSpecifications.add(model);
                }

            } catch (SpecificationDiscoveryException e) {
                addErrorMessage(errorMessages, system.getInternalServiceName(), specificationDTO.getName(), e.getMessage());
            }
        }
        addProgress();

        return new SpecificationChanges(createdGroups, createdSpecifications);
    }

    private KubeService findCorrespondingService(IntegrationSystem system, List<KubeService> services) {
        return services.stream().filter(kubeService -> kubeService.getName().equals(system.getInternalServiceName()))
                .findAny().orElse(null);
    }

    private DiscoveryResultDTO toDiscoveryResultDTO(List<IntegrationSystem> discoveredSystems,
                                                    List<SpecificationChanges> specificationChanges,
                                                    List<SpecificationDiscoveryErrorMsg> errorMessages) {

        log.debug("Discovered systems size: {}. Specification changes size: {}", discoveredSystems, specificationChanges);

        DiscoveryResultDTO discoveryResultDTO = new DiscoveryResultDTO();

        List<SpecificationGroup> discoveredGroups = specificationChanges.stream()
                .map(SpecificationChanges::getCreatedGroups).flatMap(List::stream).collect(Collectors.toList());
        List<SystemModel> discoveredSpecifications = specificationChanges.stream()
                .map(SpecificationChanges::getCreatedSpecifications).flatMap(List::stream).collect(Collectors.toList());

        discoveryResultDTO.setDiscoveredSystemIds(
                discoveredSystems.stream().map(AbstractSystemEntity::getId).collect(Collectors.toList()));
        discoveryResultDTO.setDiscoveredGroupIds(
                discoveredGroups.stream().map(AbstractSystemEntity::getId).collect(Collectors.toList()));
        discoveryResultDTO.setDiscoveredSpecificationIds(
                discoveredSpecifications.stream().map(AbstractSystemEntity::getId).collect(Collectors.toList()));
        discoveryResultDTO.setUpdatedSystemsIds(discoveredSpecifications.stream().map(SystemModel::getSpecificationGroup)
                .map(SpecificationGroup::getSystem)
                .map(AbstractSystemEntity::getId)
                .collect(Collectors.toList()));
        discoveryResultDTO.setErrorMessages(errorMessages.stream().map(
                errMsg -> new DiscoveryErrorDTO(errMsg.getServiceName(), errMsg.getErrorMessage())
        ).collect(Collectors.toList()));

        return discoveryResultDTO;
    }

    private SystemModel createSpecification(SpecificationGroup specificationGroup,
                                            SpecificationDiscoveryDTO specificationDTO,
                                            Set<String> oldSystemModelsIds,
                                            Consumer<String> messageHandler) {
        try {
            if (specificationDTO == null || StringUtils.isBlank(specificationDTO.getContent())) {
                throw new SpecificationDiscoveryException("Specification file not found");
            }

            SystemModel model = specificationImportService.importSimpleSpecification(
                    getSourceFileName(specificationGroup, specificationDTO),
                    specificationGroup.getId(),
                    specificationDTO.getType(),
                    specificationDTO.getContent(),
                    oldSystemModelsIds,
                    messageHandler).join();
            model.setSource(DISCOVERED);

            systemModelService.update(model);

            return model;
        } catch (SpecificationDiscoveryException e) {
            log.error("Synced specification creation failed", e);
            throw e;
        } catch (RuntimeException e) {
            log.error("Synced specification creation failed", e);
            throw new SpecificationDiscoveryException("Synced specification creation failed: " + e.getMessage(), e);
        }
    }

    private String getSourceFileName(SpecificationGroup specificationGroup, SpecificationDiscoveryDTO specificationDTO) {
        return StringUtils.isBlank(specificationDTO.getSourceFileName())
                ? buildSpecificationFileName(specificationGroup, specificationDTO)
                : specificationDTO.getSourceFileName();
    }

    private String buildSpecificationFileName(
            SpecificationGroup specificationGroup,
            SpecificationDiscoveryDTO specificationDiscoveryDTO
    ) {
        StringBuilder sb = new StringBuilder();
        IntegrationSystem system = specificationGroup.getSystem();
        sb.append(system.getInternalServiceName()).append('-').append(specificationGroup.getName());
        if (!StringUtils.isBlank(specificationDiscoveryDTO.getVersion())) {
            sb.append('-').append(specificationDiscoveryDTO.getVersion());
        }
        String specificationTypeName = getSpecificationTypeName(
                system.getProtocol(), specificationDiscoveryDTO.getContent(), specificationDiscoveryDTO.getUrl());
        if (!StringUtils.isBlank(specificationTypeName)) {
            sb.append('-').append(specificationTypeName);
        }
        String extension = ExportImportUtils.getExtensionByProtocolAndContentType(
                system.getProtocol(), specificationDiscoveryDTO.getContentType());
        sb.append('.').append(extension);
        return sb.toString();
    }

    public String getSpecificationTypeName(OperationProtocol protocol, String specificationText, String url) {
        return switch (protocol) {
            case HTTP -> getSpecificationTypeNameFromText(specificationText, url);
            default -> protocol.getType();
        };
    }

    private String getSpecificationTypeNameFromText(String specificationText, String url) {
        try {
            JsonNode node = isJson(specificationText)
                    ? objectMapper.readTree(specificationText)
                    : yamlMapper.readTree(specificationText);
            if (!node.isObject()) {
                log.error("Specification from URL {} is not an object", url);
                return null;
            }
            ObjectNode specObj = (ObjectNode) node;
            return specObj.has("swagger") ? "swagger" : specObj.has("openapi") ? "openapi" : null;
        } catch (JsonProcessingException ignored) {
            log.error("Error while processing specification from URL {}", url);
            return null;
        }
    }

    private String getSystemIdPostfix(String systemPostfix) {
        if (systemPostfix.isBlank()) {
            return systemPostfix;
        }
        return "-" + systemPostfix;
    }

    private SpecificationDiscoveryResult runSpecificationDiscovery(String serviceName,
                                                                   List<Integer> servicePorts,
                                                                   OperationProtocol protocol) {
        return runSpecificationDiscovery(serviceName, servicePorts, protocol, Collections.emptyList());
    }

    private SpecificationDiscoveryResult runSpecificationDiscovery(String serviceName,
                                                                   List<Integer> servicePorts,
                                                                   OperationProtocol protocol,
                                                                   List<String> ignoreUrls) {
        List<SpecificationDiscoveryDTO> specifications = null;

        // Setting priority port (speedup to check first)
        servicePorts = new ArrayList<>(servicePorts);
        if (servicePorts.remove(PRIORITY_SERVICE_PORT)) {
            servicePorts.add(0, PRIORITY_SERVICE_PORT);
        }

        String serviceAddress = null;
        for (int port : servicePorts) {
            serviceAddress = constructEnvAddress(serviceName, port);
            specifications = getServiceSpecificationsDTO(serviceAddress, protocol, ignoreUrls);
            if (!CollectionUtils.isEmpty(specifications)) {
                break;
            }
        }
        if (CollectionUtils.isEmpty(specifications)) {
            log.debug("Didn't find specifications for service {}", serviceName);
            return null;
        }

        return new SpecificationDiscoveryResult(serviceAddress, specifications);
    }

    private List<IntegrationSystem> createDiscoveredService(KubeService service, List<SpecificationDiscoveryErrorMsg> errorMessages) {
        List<IntegrationSystem> integrationSystems = new ArrayList<>(PROTOCOL_POSTFIX_MAP.keySet().size());

        for (Map.Entry<OperationProtocol, String> entry : PROTOCOL_POSTFIX_MAP.entrySet()) {
            OperationProtocol protocolType = entry.getKey();
            String systemPostfix = entry.getValue();

            if (systemService.getByIdOrNull(constructSystemId(service, systemPostfix)) != null) {
                continue;
            }

            SpecificationDiscoveryResult discoveryResult = runSpecificationDiscovery(service.getName(), service.getPorts(), protocolType);
            if (discoveryResult == null) {
                continue;
            }

            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            IntegrationSystem system = IntegrationSystem.builder()
                    .id(constructSystemId(service, systemPostfix))
                    .name(StringUtils.trim(service.getName() + " " + systemPostfix))
                    .internalServiceName(service.getName())
                    .integrationSystemType(INTERNAL)
                    .protocol(protocolType)
                    .description("")
                    .createdWhen(currentTime)
                    .modifiedWhen(currentTime)
                    .build();
            system = systemService.create(system);

            Environment environment = Environment.builder()
                    .name(service.getName())
                    .address(discoveryResult.getServiceAddress())
                    .sourceType(OperationProtocol.isAsyncProtocol(protocolType)
                            ? MAAS_BY_CLASSIFIER
                            : MANUAL)
                    .build();
            environment = environmentService.create(environment, system);

            for (SpecificationDiscoveryDTO specificationDTO : discoveryResult.getSpecificationDiscoveryDTOS()) {
                SpecificationGroup specificationGroup = specificationGroupService.createAndSaveUniqueSpecificationGroup(
                        system, specificationDTO.getName(), specificationDTO.getType(), specificationDTO.getUrl(), true);
                try {
                    createSpecification(
                            specificationGroup,
                            specificationDTO,
                            Collections.emptySet(),
                            msg -> addErrorMessage(errorMessages, service.getName(), specificationDTO.getName(), msg)
                    );
                } catch (SpecificationDiscoveryException e) {
                    addErrorMessage(errorMessages, service.getName(), specificationDTO.getName(), e.getMessage());
                }
            }

            integrationSystems.add(system);
        }

        addProgress();

        return integrationSystems;
    }

    @NotNull
    private String constructSystemId(KubeService service, String systemPostfix) {
        return service.getName() + getSystemIdPostfix(systemPostfix);
    }

    private void addErrorMessage(List<SpecificationDiscoveryErrorMsg> errorMessages, String serviceName,
                                 String specificationName, String message) {
        if (!StringUtils.isBlank(specificationName)) {
            serviceName += " " + specificationName;
        }
        errorMessages.add(new SpecificationDiscoveryErrorMsg(serviceName, message));
    }

    private String constructEnvAddress(String host, int port) {
        return host + ':' + port;
    }

    private String constructSpecAddress(String environmentAddress, String url) {
        final String protocol = "http://";
        return protocol + environmentAddress + url;
    }

    private List<SpecificationDiscoveryDTO> getServiceSpecificationsDTO(String environmentAddress, OperationProtocol protocol, List<String> ignoreUrls) {
        Map<String, String> specificationUrls = new HashMap<>();
        if (protocol == OperationProtocol.HTTP) {
            specificationUrls = getSwaggerUrls(environmentAddress);
        } else if (protocol == OperationProtocol.KAFKA) {
            specificationUrls = getAsyncUrls(environmentAddress);
        }

        if (protocol != null) {
            return getServiceSpecificationsDTO(specificationUrls, ignoreUrls, environmentAddress, protocol.getType());
        } else {
            return Collections.emptyList();
        }
    }

    private List<SpecificationDiscoveryDTO> getServiceSpecificationsDTO(Map<String, String> specificationUrls,
                                                                        List<String> ignoreUrls,
                                                                        String environmentAddress,
                                                                        String specificationType) {
        List<SpecificationDiscoveryDTO> result = new ArrayList<>();

        for (Map.Entry<String, String> entry : specificationUrls.entrySet()) {
            String url = entry.getKey();
            if (ignoreUrls.contains(url)) {
                continue;
            }
            String name = entry.getValue();
            String address = constructSpecAddress(environmentAddress, url);
            DiscoveredSpecificationSource source = getSpecificationSource(address);
            if (isNull(source) || StringUtils.isBlank(source.content())) {
                log.debug("Specification source is blank for address: {}", address);
                continue;
            }
            String version = getSpecificationRawVersion(source.content(), address);
            if (StringUtils.isBlank(version)) {
                continue;
            }

            result.add(
                    new SpecificationDiscoveryDTO(
                            specificationType,
                            name,
                            url,
                            source.contentType(),
                            source.content(),
                            version,
                            ""
                    )
            );
        }

        return result;
    }

    private HashMap<String, String> getAsyncUrls(String environmentAddress) {
        final String[] asyncConfigUrl = {"/asyncApi/specification"};

        HashMap<String, String> asyncUrls = new HashMap<>();

        for (String url : asyncConfigUrl) {
            String address = constructSpecAddress(environmentAddress, url);
            String httpResponse = getStringFromRemote(address);
            if (StringUtils.isBlank(httpResponse)) {
                continue;
            }

            try {
                JsonNode node = this.objectMapper.readTree(httpResponse);
                for (JsonNode asyncObjNode : node) {
                    String specId = asyncObjNode.get(SPEC_FIELD_ID).asText();
                    String specVersion = asyncObjNode.get(SPEC_FIELD_VERSION).asText();
                    String specName = asyncObjNode.get(SPEC_FIELD_NAME) == null ? null : asyncObjNode.get(SPEC_FIELD_NAME).asText();
                    if (StringUtils.isBlank(specName)) {
                        specName = specId;
                    }
                    asyncUrls.put(url + "/" + specId + "/version/" + specVersion, specName);
                }
            } catch (IOException | NullPointerException ignored) {
                log.error("Error while reading response from address {}", address);
            }
        }

        return asyncUrls;
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    private HashMap<String, String> getSwaggerUrls(String environmentAddress) {
        final String[] swaggerConfigUrl = {"/v3/api-docs/swagger-config", "/swagger-resources"};
        final String[] swaggerDefaultUrl = {"/q/openapi", "/v3/api-docs", "/v2/api-docs", "/v1/api-docs", "/api-docs", "/swagger-ui/swagger.json"};
        final String defaultSpecName = "default";

        HashMap<String, String> swaggerUrls = new HashMap<>();

        for (String url : swaggerConfigUrl) {
            String address = constructSpecAddress(environmentAddress, url);
            String httpResponse = getStringFromRemote(address);
            if (StringUtils.isBlank(httpResponse)) {
                continue;
            }

            try {
                JsonNode node = this.objectMapper.readTree(httpResponse);
                if (node.has(SPEC_FIELD_URL)) {
                    swaggerUrls.put(node.get(SPEC_FIELD_URL).asText(), defaultSpecName);
                } else if (node.has(SPEC_FIELD_URL_LIST) || node.isArray()) {
                    JsonNode rootArrUrlNode = node.has(SPEC_FIELD_URL_LIST) ? node.get(SPEC_FIELD_URL_LIST) : node;
                    for (JsonNode urlNode : rootArrUrlNode) {
                        String specName = urlNode.has(SPEC_FIELD_NAME) ? urlNode.get(SPEC_FIELD_NAME).asText() : null;
                        if (StringUtils.isBlank(specName)) {
                            specName = urlNode.get(SPEC_FIELD_URL).asText();
                        }
                        swaggerUrls.put(urlNode.get(SPEC_FIELD_URL).asText(), specName);
                    }
                }
            } catch (IOException | NullPointerException ignored) {
            }
        }

        if (swaggerUrls.isEmpty()) {
            for (String url : swaggerDefaultUrl) {
                swaggerUrls.put(url, defaultSpecName);
            }
        }

        return swaggerUrls;
    }

    private String getStringFromRemote(String address) {
        try {
            return restTemplate.getForObject(address, String.class);
        } catch (Exception e) {
            log.error("Error while receiving spec from address: {}, exception: {}", address, e.getMessage());
            return null;
        }
    }

    private DiscoveredSpecificationSource getSpecificationSource(String address) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(address, String.class);
            String type = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            String text = response.getBody();
            return new DiscoveredSpecificationSource(type, text);
        } catch (Exception e) {
            return null;
        }
    }

    private String getSpecificationRawVersion(String specificationContent, String address) {
        String version;

        try {
            try {
                version = this.objectMapper.readTree(specificationContent).get(SPEC_FIELD_INFO).get(SPEC_FIELD_VERSION).asText("");
            } catch (IOException | NullPointerException e) {
                version = this.yamlMapper.readTree(specificationContent).get(SPEC_FIELD_INFO).get(SPEC_FIELD_VERSION).asText("");
            }
        } catch (IOException | NullPointerException e) {
            log.error("Error while parsing version from address: {}", address);
            return null;
        }

        return version;
    }

    public List<DiscoveredServiceDTO> getServices() {
        return discoveryServiceMapper.toDiscoveredServiceDTOs(systemService.getAllDiscoveredServices());
    }

    private void setDiscoveryStatusResult(DiscoveryResultDTO result, String errorMessage) {
        setDiscoveryStatus(DISCOVERY_COMPLETE);
        ConfigParameter cp = new ConfigParameter(DISCOVERY_NAMESPACE, DISCOVERY_RESULT_NAME);
        DiscoveryStatusDTO dto = new DiscoveryStatusDTO(result, errorMessage);
        try {
            cp.setString(objectMapper.writeValueAsString(dto));
            configParameterService.update(cp);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Unable to set discovery status: " + e.getMessage());
        }
    }

    private void setDiscoveryStatus(String discoveryProgress) {
        ConfigParameter cp = new ConfigParameter(DISCOVERY_NAMESPACE, DISCOVERY_PROGRESS_NAME);
        cp.setString(discoveryProgress);
        configParameterService.update(cp);
        configParameterService.flush();
    }

    public String getDiscoveryProgress() {
        final int entityExpiredTimeoutMinutes = 15;

        ConfigParameter cp = configParameterService.findByName(DISCOVERY_NAMESPACE, DISCOVERY_PROGRESS_NAME);
        String progress = cp == null ? null : cp.getString().split("\\.")[0];

        if (progress == null
                || cp.getModifiedWhen().before(
                        Timestamp.valueOf(LocalDateTime.now().minusMinutes(entityExpiredTimeoutMinutes)))) {
            return DISCOVERY_COMPLETE;
        }

        return progress;
    }

    public boolean isDiscoveryComplete() {
        return getDiscoveryProgress().equals(DISCOVERY_COMPLETE);
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public DiscoveryResultDTO getDiscoveryResult() {
        ConfigParameter cp = configParameterService.findByName(DISCOVERY_NAMESPACE, DISCOVERY_RESULT_NAME);
        String rawStatusData = cp == null ? null : cp.getString();
        DiscoveryStatusDTO status = new DiscoveryStatusDTO();
        try {
            status = objectMapper.readValue(rawStatusData, DiscoveryStatusDTO.class);
        } catch (JsonProcessingException | RuntimeException ignored) {
        }


        if (status == null) {
            throw new RuntimeException("Unknown error, no status available");
        }

        String errorMessage = status.getErrorMessage();
        if (!StringUtils.isBlank(errorMessage)) {
            throw new RuntimeException("Error in Autodiscovery service: " + errorMessage);
        }

        return status.getResult();
    }

    private void addProgress() {
        synchronized (lock) {
            ConfigParameter cp = configParameterService.findByName(DISCOVERY_NAMESPACE, DISCOVERY_PROGRESS_NAME);
            double currentProgress = cp == null ? 0 : Double.parseDouble(cp.getString());
            log.info("Current progress: {}", currentProgress);
            setDiscoveryStatus(String.valueOf(currentProgress + percent));
        }
    }
}
