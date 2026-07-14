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
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.parsers.Parser;
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
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * Catalog entry point for Swagger and OpenAPI imports.
 *
 * <p>Pure spec parsing lives in the library {@link org.qubership.integration.platform.parsers.impl.SwaggerSpecificationParser}.
 * This wrapper keeps the one part that touches persistence: it resolves the owning system's
 * environments from the specification's {@code servers}. The source is parsed once, so environment
 * resolution and operation parsing read the same {@link OpenAPI} model, and both run in the same
 * order as before the split.
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

            resolverSwaggerEnvironment(group, importedOpenAPI);

            return parsedSystemModel;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private void resolverSwaggerEnvironment(SpecificationGroup specificationGroup, OpenAPI importedOpenAPI) {
        if (importedOpenAPI.getServers() != null && !importedOpenAPI.getServers().isEmpty()) {
            switch (specificationGroup.getSystem().getIntegrationSystemType()) {
                case EXTERNAL:
                    if (specificationGroup.getSystem().getEnvironments().isEmpty()) {
                        for (Server server : importedOpenAPI.getServers()) {
                            String name = "Environment for " + specificationGroup.getName() + " specification group";
                            if (server.getDescription() != null) {
                                name = server.getDescription();
                            }
                            String url = getUrlWithoutPlaceHolders(server);
                            Environment environment = Environment.builder()
                                    .name(name)
                                    .address(url)
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
                    if (StringUtils.isBlank(environment.getAddress())
                            && !CollectionUtils.isEmpty(importedOpenAPI.getServers())) {
                        environment.setAddress(getUrlWithoutPlaceHolders(importedOpenAPI.getServers().getFirst()));
                        environmentBaseService.update(environment);
                    }
                    break;
                case IMPLEMENTED:
                    setDefaultProperties(specificationGroup);
                    break;
            }
        }
    }

    private Environment setDefaultProperties(SpecificationGroup specificationGroup) {
        Environment environment = specificationGroup.getSystem().getEnvironments().getFirst();
        environmentBaseService.setDefaultProperties(environment);
        return environment;
    }

    private String getUrlWithoutPlaceHolders(Server server) {
        final String[] url = {server.getUrl()};
        if (server.getVariables() != null && !server.getVariables().isEmpty()) {
            server.getVariables().forEach((key, value) -> {
                if (url[0].contains("{" + key + "}")) {
                    url[0] = url[0].replace("{" + key + "}", value.getDefault());
                }
            });
        }
        url[0] = StringUtils.strip(url[0], "/");
        return url[0];
    }
}
