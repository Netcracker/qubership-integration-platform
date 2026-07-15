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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.UrlValidator;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.impl.WsdlSpecificationParser;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.wsdl.WsdlEndpoint;
import org.qubership.integration.platform.parsers.model.wsdl.WsdlParseResult;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.EnvironmentMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType.EXTERNAL;

/**
 * Catalog entry point for WSDL (SOAP) imports.
 *
 * <p>Pure spec parsing lives in the library {@link WsdlSpecificationParser}. This wrapper keeps the
 * two parts that touch the catalog: it adapts the persistence sources to the library shape, then
 * registers the owning system's environments from the endpoints the library returns. Parsing runs
 * once, so operation parsing and environment resolution share the same result, and both run in the
 * same order as before the split: operations first, then environments.
 */
@Slf4j
@Service
@Parser("soap")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class WSDLSpecificationParser implements org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser {

    private final EnvironmentBaseService environmentBaseService;
    private final EnvironmentMapper environmentMapper;
    private final WsdlSpecificationParser libraryWsdlParser;

    @Autowired
    public WSDLSpecificationParser(
            EnvironmentBaseService environmentBaseService,
            EnvironmentMapper environmentMapper,
            WsdlSpecificationParser libraryWsdlParser
    ) {
        this.environmentBaseService = environmentBaseService;
        this.environmentMapper = environmentMapper;
        this.libraryWsdlParser = libraryWsdlParser;
    }

    @Override
    public ParsedSystemModel parseSpecification(
            SpecificationGroup group,
            Collection<org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            List<SpecificationSource> librarySources = new ArrayList<>();
            SpecificationSource mainSource = null;
            for (var source : sources) {
                SpecificationSource librarySource = new SpecificationSource(source.getName(), source.getSource());
                librarySources.add(librarySource);
                if (source.isMainSource()) {
                    mainSource = librarySource;
                }
            }
            if (mainSource == null) {
                throw new SpecificationImportException("Couldn't determine main specification source");
            }

            WsdlParseResult result = libraryWsdlParser.parse(librarySources, mainSource);

            setUpEnvironments(group, result.endpoints());

            return result.systemModel();
        } catch (SpecificationImportException e) {
            throw e;
        } catch (SpecificationParserException e) {
            throw new SpecificationImportException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private void setUpEnvironments(SpecificationGroup specificationGroup, List<WsdlEndpoint> endpoints) {
        if (specificationGroup.getSystem() == null
                || !EXTERNAL.equals(specificationGroup.getSystem().getIntegrationSystemType())) {
            return;
        }
        for (WsdlEndpoint endpoint : endpoints) {
            addEnvironment(specificationGroup, endpoint.name(), endpoint.address());
        }
    }

    private void addEnvironment(SpecificationGroup specificationGroup, String envName, String envURL) {
        UrlValidator urlValidator = new UrlValidator();
        if (urlValidator.isValid(envURL)) {
            EnvironmentRequestDTO requestDTO = new EnvironmentRequestDTO();
            requestDTO.setName(envName);
            requestDTO.setAddress(envURL);
            Environment environment = environmentMapper.toEnvironment(requestDTO);
            environmentBaseService.create(environment, specificationGroup.getSystem());
        }
    }
}
