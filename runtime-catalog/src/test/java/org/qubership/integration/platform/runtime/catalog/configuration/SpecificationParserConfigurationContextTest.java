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

package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.ParserOptions;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParser;
import org.qubership.integration.platform.parsers.configuration.SpecificationParserConfiguration;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.OperationParserService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.parsers.SAXParserFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the specification-parser wiring starts without a full application context.
 *
 * <p>{@link SpecificationParserConfiguration} builds the five library parsers, and
 * {@link OperationParserService} collects them by their {@code @Parser} protocol. This test loads
 * only those two, backed by lightweight beans for the parsers' collaborators and Mockito mocks for
 * the service's persistence dependencies, and confirms the context starts, every parser is present,
 * and the service is constructed. The {@code @Lazy EnvironmentBaseService} dependency breaks the
 * bean cycle, so the context must still start with a mock in its place.
 */
class SpecificationParserConfigurationContextTest {

    private static final Set<String> EXPECTED_PARSER_PROTOCOLS =
            Set.of("graphqlschema", "protobuf", "swagger", "asyncapi", "soap");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean("primaryObjectMapper", ObjectMapper.class, ObjectMapper::new)
            .withBean("openApiObjectMapper", ObjectMapper.class, ObjectMapper::new)
            .withBean("wsdlVersionSaxParserFactory", SAXParserFactory.class, SAXParserFactory::newInstance)
            .withBean("graphqlOperationParserOptions", ParserOptions.class,
                    ParserOptions::getDefaultOperationParserOptions)
            .withBean(graphql.parser.Parser.class, graphql.parser.Parser::new)
            .withBean(OperationRepository.class, () -> mock(OperationRepository.class))
            .withBean(SystemModelRepository.class, () -> mock(SystemModelRepository.class))
            .withBean(SpecificationGroupRepository.class, () -> mock(SpecificationGroupRepository.class))
            .withBean(SpecificationSourceRepository.class, () -> mock(SpecificationSourceRepository.class))
            .withBean(SystemModelBaseService.class, () -> mock(SystemModelBaseService.class))
            .withBean(ActionsLogService.class, () -> mock(ActionsLogService.class))
            .withBean(TransactionHandler.class, () -> mock(TransactionHandler.class))
            .withBean(EnvironmentBaseService.class, () -> mock(EnvironmentBaseService.class))
            .withUserConfiguration(SpecificationParserConfiguration.class)
            .withBean(OperationParserService.class);

    @Test
    void contextStartsWithAllParsersAndOperationParserService() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            Set<String> registeredProtocols = context.getBeansOfType(SpecificationParser.class).values().stream()
                    .map(parser -> parser.getClass().getAnnotation(Parser.class))
                    .filter(Objects::nonNull)
                    .map(Parser::value)
                    .collect(Collectors.toSet());
            assertThat(registeredProtocols).isEqualTo(EXPECTED_PARSER_PROTOCOLS);

            assertThat(context).hasSingleBean(OperationParserService.class);
        });
    }
}
