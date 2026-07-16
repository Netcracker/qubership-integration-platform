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

package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import io.swagger.v3.oas.models.OpenAPI;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * Catalog entry point for Swagger and OpenAPI imports.
 *
 * <p>Pure spec parsing lives in the library {@link org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser},
 * which emits the declared servers as environments in its model with their URLs already
 * placeholder-stripped. This wrapper keeps the one part that touches persistence: it reconciles the
 * owning system's environments from those parsed environments, preserving Swagger's per-protocol
 * rules (HTTP default properties, the INTERNAL in-place address update, and the EXTERNAL and
 * IMPLEMENTED branches). Operations parse first, then environments, as before the split.
 */
@Slf4j
@Service
@Parser("swagger")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class SwaggerSpecificationParser implements SpecificationParser {

    private final org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser librarySwaggerParser;
    private final EnvironmentBaseService environmentBaseService;

    @Autowired
    public SwaggerSpecificationParser(
            org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser librarySwaggerParser,
            EnvironmentBaseService environmentBaseService
    ) {
        this.librarySwaggerParser = librarySwaggerParser;
        this.environmentBaseService = environmentBaseService;
    }

    @Override
    public ParsedSystemModel parseSpecification(
            SpecificationGroup group,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            List<org.qubership.integration.platform.parsers.SpecificationSource> librarySources = sources.stream()
                    .map(source -> new org.qubership.integration.platform.parsers.SpecificationSource(
                            source.getName(), source.getSource()))
                    .collect(Collectors.toList());

            OpenAPI importedOpenAPI = librarySwaggerParser.parseOpenApi(librarySources, messageHandler);
            ParsedSystemModel parsedSystemModel = librarySwaggerParser.toSystemModel(importedOpenAPI, messageHandler);

            resolveSwaggerEnvironments(parsedSystemModel.getEnvironments(), group);

            return parsedSystemModel;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    /**
     * Reconciles the owning system's environments from the parsed environments, preserving Swagger's
     * per-protocol rules. An EXTERNAL system with no environments yet gains one per parsed
     * environment; an INTERNAL system fills a blank address in place from the first parsed
     * environment; both, along with IMPLEMENTED, receive the protocol's default properties. The
     * address is already placeholder-stripped in the library; a parsed environment with no name
     * falls back to a spec-group-derived name.
     *
     * <p>Kept in the wrapper for now; slice 4 hoists it into the operation parser. It takes the parsed
     * environments and the specification group, which carries both the owning system and the fallback
     * name, so the reconcile can move without a rewrite.
     */
    private void resolveSwaggerEnvironments(List<ParsedEnvironment> parsedEnvironments, SpecificationGroup specificationGroup) {
        if (parsedEnvironments != null && !parsedEnvironments.isEmpty()) {
            switch (specificationGroup.getSystem().getIntegrationSystemType()) {
                case EXTERNAL:
                    if (specificationGroup.getSystem().getEnvironments().isEmpty()) {
                        for (ParsedEnvironment parsedEnvironment : parsedEnvironments) {
                            Environment environment = Environment.builder()
                                    .name(environmentName(parsedEnvironment, specificationGroup))
                                    .address(parsedEnvironment.getAddress())
                                    .labels(new ArrayList<>())
                                    .sourceType(EnvironmentSourceType.MANUAL)
                                    .build();
                            environmentBaseService.create(environment, specificationGroup.getSystem());
                            setDefaultProperties(specificationGroup);
                        }
                    }
                    break;
                case INTERNAL:
                    Environment environment = setDefaultProperties(specificationGroup);
                    if (StringUtils.isBlank(environment.getAddress())) {
                        environment.setAddress(parsedEnvironments.getFirst().getAddress());
                        environmentBaseService.update(environment);
                    }
                    break;
                case IMPLEMENTED:
                    setDefaultProperties(specificationGroup);
                    break;
            }
        }
    }

    private String environmentName(ParsedEnvironment parsedEnvironment, SpecificationGroup specificationGroup) {
        if (parsedEnvironment.getName() != null) {
            return parsedEnvironment.getName();
        }
        return "Environment for " + specificationGroup.getName() + " specification group";
    }

    private Environment setDefaultProperties(SpecificationGroup specificationGroup) {
        Environment environment = specificationGroup.getSystem().getEnvironments().getFirst();
        environmentBaseService.setDefaultProperties(environment);
        return environment;
    }
}
