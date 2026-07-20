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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentDefaultParameters;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
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
     * Reconciles the environments a parser declared against the owning system.
     *
     * <p>Maps each protocol-agnostic {@link ParsedEnvironment} a library parser produces to an
     * {@code Environment} through {@link #createEnvironmentFromParsed}, then hands the candidates to
     * {@link #reconcileEnvironments}.
     */
    public void resolveEnvironments(List<ParsedEnvironment> parsedEnvironments,
                                    IntegrationSystem system,
                                    OperationProtocol operationProtocol,
                                    Consumer<String> messageHandler) {
        List<Environment> candidates = parsedEnvironments == null
                ? new ArrayList<>()
                : parsedEnvironments.stream()
                        .map(parsedEnvironment -> createEnvironmentFromParsed(parsedEnvironment, operationProtocol))
                        .toList();
        reconcileEnvironments(candidates, system, messageHandler);
    }

    /**
     * Reconciles a WSDL import's environments against the owning system. Only an EXTERNAL system
     * takes them, and only endpoints whose address is a valid URL count. The valid endpoints then go
     * through the shared reconcile path.
     *
     * <p>Unlike the AMQP and HTTP protocols, WSDL environments carry no default properties. The SOAP
     * system reports {@code "http"} as its protocol, so routing WSDL endpoints through
     * {@link #createEnvironmentFromParsed} would stamp them with Kafka defaults; building the
     * candidates here keeps their properties unset, as the pre-extraction WSDL path did.
     *
     * @param parsedEnvironments the endpoints the WSDL declares
     * @param system the owning system; a non-EXTERNAL or {@code null} system is left untouched
     */
    public void resolveWsdlEnvironments(List<ParsedEnvironment> parsedEnvironments,
                                        IntegrationSystem system,
                                        Consumer<String> messageHandler) {
        if (system == null || !IntegrationSystemType.EXTERNAL.equals(system.getIntegrationSystemType())) {
            return;
        }
        UrlValidator urlValidator = new UrlValidator();
        List<Environment> candidates = parsedEnvironments.stream()
                .filter(environment -> urlValidator.isValid(environment.getAddress()))
                .map(this::createWsdlEnvironment)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        reconcileEnvironments(candidates, system, messageHandler);
    }

    private Environment createWsdlEnvironment(ParsedEnvironment parsedEnvironment) {
        // No properties: a SOAP system reports "http" as its protocol, so the shared path would stamp
        // Kafka defaults on it. An empty label list (never null) keeps the reconcile dedup comparison
        // from tripping over CompareListUtils on a null-versus-null labels pair.
        return Environment.builder()
                .name(parsedEnvironment.getName())
                .address(parsedEnvironment.getAddress())
                .labels(new ArrayList<>())
                .build();
    }

    /**
     * Reconciles a Swagger import's environments against the owning system, preserving Swagger's
     * per-protocol rules. An EXTERNAL system with no environments yet gains one per parsed
     * environment; an INTERNAL system fills a blank address in place from the first parsed
     * environment; both, along with IMPLEMENTED, receive the protocol's default properties. The
     * address is already placeholder-stripped by the parser; a parsed environment with no name falls
     * back to a spec-group-derived name.
     *
     * @param parsedEnvironments the servers the specification declares
     * @param specificationGroup carries both the owning system and the fallback name
     */
    public void resolveSwaggerEnvironments(List<ParsedEnvironment> parsedEnvironments,
                                           SpecificationGroup specificationGroup) {
        if (parsedEnvironments == null || parsedEnvironments.isEmpty()) {
            return;
        }
        switch (specificationGroup.getSystem().getIntegrationSystemType()) {
            case EXTERNAL:
                if (specificationGroup.getSystem().getEnvironments().isEmpty()) {
                    for (ParsedEnvironment parsedEnvironment : parsedEnvironments) {
                        Environment environment = Environment.builder()
                                .name(swaggerEnvironmentName(parsedEnvironment, specificationGroup))
                                .address(parsedEnvironment.getAddress())
                                .labels(new ArrayList<>())
                                .sourceType(EnvironmentSourceType.MANUAL)
                                .build();
                        create(environment, specificationGroup.getSystem());
                        setSwaggerDefaultProperties(specificationGroup);
                    }
                }
                break;
            case INTERNAL:
                Environment environment = setSwaggerDefaultProperties(specificationGroup);
                if (StringUtils.isBlank(environment.getAddress())) {
                    environment.setAddress(parsedEnvironments.get(0).getAddress());
                    update(environment);
                }
                break;
            case IMPLEMENTED:
                setSwaggerDefaultProperties(specificationGroup);
                break;
            default:
                break;
        }
    }

    private String swaggerEnvironmentName(ParsedEnvironment parsedEnvironment, SpecificationGroup specificationGroup) {
        if (parsedEnvironment.getName() != null) {
            return parsedEnvironment.getName();
        }
        return "Environment for " + specificationGroup.getName() + " specification group";
    }

    private Environment setSwaggerDefaultProperties(SpecificationGroup specificationGroup) {
        Environment environment = specificationGroup.getSystem().getEnvironments().get(0);
        setDefaultProperties(environment);
        return environment;
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

    /**
     * Reconciles a system's environments against the ones a specification declares.
     *
     * <p>An EXTERNAL system gains every declared environment that it does not already hold; an
     * INTERNAL system keeps at most one, replacing a MANUAL placeholder that carries a blank address.
     * When the specification declares nothing, an INTERNAL system falls back to empty protocol
     * properties and the caller receives an explanatory message.
     *
     * @param candidateEnvironments the environments the specification declares, in declaration order
     */
    protected void reconcileEnvironments(List<Environment> candidateEnvironments,
                                         IntegrationSystem system,
                                         Consumer<String> messageHandler) {
        List<Environment> environments = system.getEnvironments();

        if (!candidateEnvironments.isEmpty()) {
            switch (system.getIntegrationSystemType()) {
                case EXTERNAL -> {
                    for (Environment newEnv : candidateEnvironments) {
                        boolean sameEnvNotExists = system.getEnvironments().stream().noneMatch(env -> env.equals(newEnv, false));
                        if (sameEnvNotExists) {
                            create(newEnv, system);
                        }
                    }
                }
                case INTERNAL -> {
                    boolean envsIsEmpty = environments.isEmpty();
                    if (envsIsEmpty || (environments.getFirst().getSourceType() == EnvironmentSourceType.MANUAL && StringUtils.isBlank(environments.getFirst().getAddress()))) {
                        Environment newEnv = candidateEnvironments.getFirst();

                        if (!envsIsEmpty) {
                            Environment oldEnvironment = environments.getFirst();
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

    protected Environment createEnvironmentFromParsed(
            ParsedEnvironment parsedEnvironment,
            OperationProtocol operationProtocol) {
        return Environment.builder()
                .name(parsedEnvironment.getName())
                .address(parsedEnvironment.getAddress())
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .properties(parserUtils.receiveEmptyProperties(operationProtocol))
                .build();
    }
}
