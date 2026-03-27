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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentDefaultParameters;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Server;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.EnvironmentRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class EnvironmentBaseService {

    protected static final String SPECIFICATION_PARAMETERS_ARE_EMPTY_MESSAGE = "Server parameters are empty in input specification";

    protected final EnvironmentRepository environmentRepository;
    protected final SystemBaseService systemBaseService;
    protected final ActionsLogService actionLogger;
    protected final ObjectMapper jsonMapper;
    protected final ParserUtils parserUtils;

    @Autowired
    public EnvironmentBaseService(
            EnvironmentRepository environmentRepository,
            SystemBaseService systemBaseService,
            ActionsLogService actionLogger,
            @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper,
            ParserUtils parserUtils
    ) {
        this.environmentRepository = environmentRepository;
        this.systemBaseService = systemBaseService;
        this.actionLogger = actionLogger;
        this.jsonMapper = jsonMapper;
        this.parserUtils = parserUtils;
    }

    private Environment save(Environment environment) {
        return environmentRepository.save(environment);
    }

    @Transactional
    public Environment update(Environment environment) {
        Environment savedEnv = environmentRepository.save(environment);

        activateDefaultEnvForExternalSystem(environment, environment.getSystem());
        logEnvironmentAction(savedEnv, LogOperation.UPDATE);
        return savedEnv;
    }

    @Transactional
    public Environment create(Environment environment, IntegrationSystem system) {
        environment = save(environment);
        system.addEnvironment(environment);

        activateDefaultEnvForExternalSystem(environment, system);
        logEnvironmentAction(environment, system, LogOperation.CREATE);
        return environment;
    }

    @Transactional
    public void setDefaultProperties(Environment environment) {
        OperationProtocol protocol = environment.getSystem().getProtocol();
        if (null != protocol && (environment.getProperties() == null || environment.getProperties().isEmpty())) {
            switch (environment.getSystem().getProtocol()) {
                case HTTP -> setDefaultProperties(environment, EnvironmentDefaultParameters.HTTP_ENVIRONMENT_PARAMETERS);
                case KAFKA -> {
                    if (environment.getSourceType() == EnvironmentSourceType.MANUAL) {
                        setDefaultProperties(environment, EnvironmentDefaultParameters.KAFKA_ENVIRONMENT_PARAMETERS);
                    } else {
                        setDefaultProperties(environment, EnvironmentDefaultParameters.MAAS_BY_CLASSIFIER_KAFKA_ENVIRONMENT_PARAMETERS);
                    }
                }
                case AMQP -> {
                    if (environment.getSourceType() == EnvironmentSourceType.MANUAL) {
                        setDefaultProperties(environment, EnvironmentDefaultParameters.RABBIT_ENVIRONMENT_PARAMETERS);
                    } else {
                        setDefaultProperties(environment, EnvironmentDefaultParameters.MAAS_BY_CLASSIFIER_RABBIT_ENVIRONMENT_PARAMETERS);
                    }
                }
            }
        }
    }

    protected void setDefaultProperties(Environment environment, Map<String, String> defaultProperties) {
        environment.setProperties(jsonMapper.convertValue(defaultProperties, JsonNode.class));
    }

    /**
     * Resolve environments from specification
     */
    public void resolveEnvironments(AsyncapiSpecification importedAsyncApi,
                                    OperationProtocol operationProtocol,
                                    IntegrationSystem system,
                                    Consumer<String> messageHandler) {
        try {
            resolveEnvironmentsForServers(
                    importedAsyncApi.getServers(),
                    system,
                    operationProtocol,
                    messageHandler);
        } catch (Exception e) {
            log.warn("Failed to resolve environments", e);
            messageHandler.accept("Failed to resolve environments, " + e.getMessage());
        }
    }

    protected void activateDefaultEnvForExternalSystem(Environment environment, IntegrationSystem system) {
        if (system.getIntegrationSystemType() == IntegrationSystemType.EXTERNAL && system.getActiveEnvironmentId() == null) {
            activateEnvironmentByDefault(environment);
        }
    }

    /**
     * this method is use for when upload the API specification then create first environment and it's activate automatically
     *
     * @param environment
     */
    protected void activateEnvironmentByDefault(Environment environment) {
        String address = environment.getAddress();
        IntegrationSystem system = environment.getSystem();
        if (StringUtils.isNotEmpty(address)) {
            system.setActiveEnvironmentId(environment.getId());
            systemBaseService.update(system);
        }
    }

    protected void logEnvironmentAction(Environment environment, LogOperation operation) {
        logEnvironmentAction(environment, environment.getSystem(), operation);
    }

    protected void logEnvironmentAction(Environment environment, IntegrationSystem system, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.ENVIRONMENT)
                .entityId(environment.getId())
                .entityName(environment.getName())
                .parentId(system == null ? null : system.getId())
                .parentName(system == null ? null : system.getName())
                .parentType(system == null ? null : EntityType.getSystemType(system))
                .operation(operation)
                .build());
    }

    protected void resolveEnvironmentsForServers(Map<String, Server> specServers,
                                               IntegrationSystem system,
                                               OperationProtocol operationProtocol,
                                               Consumer<String> messageHandler) throws EntityNotFoundException {
        List<Environment> environments = system.getEnvironments();

        if (specServers != null && !specServers.isEmpty()) {
            switch (system.getIntegrationSystemType()) {
                case EXTERNAL -> {
                    for (Map.Entry<String, Server> serverEntry : specServers.entrySet()) {
                        Environment newEnv = createEnvironmentFromSpecServer(
                                serverEntry.getKey(),
                                serverEntry.getValue(),
                                operationProtocol);

                        boolean sameEnvNotExists = system.getEnvironments().stream().noneMatch(env -> env.equals(newEnv, false));
                        if (sameEnvNotExists) {
                            create(newEnv, system);
                        }
                    }
                }
                case INTERNAL -> {
                    boolean envsIsEmpty = environments.isEmpty();
                    if (envsIsEmpty || (environments.get(0).getSourceType() == EnvironmentSourceType.MANUAL && StringUtils.isBlank(environments.get(0).getAddress()))) {
                        Map.Entry<String, Server> serverEntry = specServers.entrySet().stream()
                                .findFirst()
                                .orElseThrow(() -> new EntityNotFoundException(SPECIFICATION_PARAMETERS_ARE_EMPTY_MESSAGE));

                        Environment newEnv = createEnvironmentFromSpecServer(
                                serverEntry.getKey(),
                                serverEntry.getValue(),
                                operationProtocol);

                        if (!envsIsEmpty) {
                            Environment oldEnvironment = environments.get(0);
                            system.removeEnvironment(oldEnvironment);
                            environmentRepository.delete(oldEnvironment);
                        }
                        create(newEnv, system);
                    }
                }
            }
        } else {
            if (system.getIntegrationSystemType() == IntegrationSystemType.INTERNAL) {
                setEmptyPropertiesForUsedProtocol(system);
            }
            messageHandler.accept(SPECIFICATION_PARAMETERS_ARE_EMPTY_MESSAGE);
        }
    }

    protected void setEmptyPropertiesForUsedProtocol(IntegrationSystem system) {
        system
                .getEnvironments().stream()
                .filter(env -> env.getProperties() == null || env.getProperties().isEmpty())
                .forEach(env -> env.setProperties(parserUtils.receiveEmptyProperties(system.getProtocol())));
    }

    protected Environment createEnvironmentFromSpecServer(
            String name,
            Server server,
            OperationProtocol operationProtocol) {
        return Environment.builder()
                .name(name)
                .address(server.getUrl())
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .properties(parserUtils.receiveEmptyProperties(operationProtocol))
                .build();
    }
}
