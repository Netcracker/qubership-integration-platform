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

package org.qubership.integration.platform.engine.service.externallibrary;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.language.groovy.GroovyShellFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class ExternalLibraryGroovyShellFactory implements GroovyShellFactory {
    private final Optional<ExternalLibraryService> externalLibraryService;

    @Autowired
    public ExternalLibraryGroovyShellFactory(Optional<ExternalLibraryService> externalLibraryService) {
        this.externalLibraryService = externalLibraryService;
    }

    @Override
    public GroovyShell createGroovyShell(Exchange exchange) {
        log.debug("Requesting groovy shell for {}", isNull(exchange)? Collections.emptyMap() : exchange.getProperties());
        GroovyClassLoader groovyClassLoader = new GroovyClassLoader(externalLibraryService.isPresent() ?
            externalLibraryService.get().getShellClassLoader() : getClass().getClassLoader());
        return new GroovyShell(groovyClassLoader);
    }
}
