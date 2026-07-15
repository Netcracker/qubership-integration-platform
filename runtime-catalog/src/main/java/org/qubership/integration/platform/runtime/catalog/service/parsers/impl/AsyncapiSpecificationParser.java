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
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Catalog entry point for AsyncAPI imports.
 *
 * <p>Pure spec parsing lives in the library {@link org.qubership.integration.platform.parsers.impl.AsyncapiSpecificationParser}.
 * This wrapper keeps the one part that touches persistence: it resolves the owning system's
 * environments from the specification's {@code servers}. The source is read once, so operation
 * parsing and environment resolution share the same {@link AsyncapiSpecification} model, and both
 * run in the same order as before the split: operations first, then environments.
 */
@Slf4j
@Service
@Parser("asyncapi")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class AsyncapiSpecificationParser implements SpecificationParser {

    private final EnvironmentBaseService environmentBaseService;
    private final org.qubership.integration.platform.parsers.impl.AsyncapiSpecificationParser libraryAsyncapiParser;

    @Autowired
    public AsyncapiSpecificationParser(
            @Lazy EnvironmentBaseService environmentBaseService,
            org.qubership.integration.platform.parsers.impl.AsyncapiSpecificationParser libraryAsyncapiParser
    ) {
        this.environmentBaseService = environmentBaseService;
        this.libraryAsyncapiParser = libraryAsyncapiParser;
    }

    @Override
    public ParsedSystemModel parseSpecification(
            SpecificationGroup group,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
            AsyncapiSpecification importedAsyncApi = libraryAsyncapiParser.read(specificationText);

            OperationProtocol operationProtocol = group.getSystem().getProtocol();
            String protocol = operationProtocol == null ? null : operationProtocol.getValue();

            ParsedSystemModel parsedSystemModel = libraryAsyncapiParser.toSystemModel(importedAsyncApi, protocol);

            environmentBaseService.resolveEnvironments(
                    importedAsyncApi,
                    operationProtocol,
                    group.getSystem(),
                    messageHandler);

            return parsedSystemModel;
        } catch (SpecificationImportException e) {
            throw e;
        } catch (SpecificationParserException e) {
            throw new SpecificationImportException(e.getMessage(), e.getCause());
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }
}
