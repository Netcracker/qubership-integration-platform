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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedEnvironmentImpl;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentDefaultParameters;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.EnvironmentRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pins which protocols stamp default environment properties onto an imported environment, using the
 * real {@link ParserUtils} rather than a mock. WSDL carries none; Swagger carries the HTTP defaults;
 * AsyncAPI carries the Kafka or Rabbit defaults. The WSDL case is the regression guard: a SOAP
 * system reports {@code "http"} as its protocol, so the shared path would otherwise stamp Kafka
 * defaults onto it.
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentDefaultPropertiesTest {

    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private SystemBaseService systemBaseService;
    @Mock
    private ActionsLogService actionLogger;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParserUtils parserUtils = new ParserUtils(objectMapper);
    private final List<String> messages = new ArrayList<>();
    private final Consumer<String> messageHandler = messages::add;

    private EnvironmentBaseService service;

    @BeforeEach
    void setUp() {
        service = new EnvironmentBaseService(
                environmentRepository, systemBaseService, actionLogger, objectMapper, parserUtils);
        when(environmentRepository.save(any(Environment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private IntegrationSystem system(IntegrationSystemType type, OperationProtocol protocol) {
        return IntegrationSystem.builder()
                .id("system-1")
                .name("test system")
                .integrationSystemType(type)
                .protocol(protocol)
                .build();
    }

    @Test
    @DisplayName("WSDL: an EXTERNAL SOAP system persists an environment with no properties")
    void wsdlPersistsNoProperties() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL, OperationProtocol.SOAP);

        service.resolveWsdlEnvironments(
                List.of(ParsedEnvironmentImpl.builder().name("port").address("http://example.com/service").build()),
                system, messageHandler);

        assertThat(system.getEnvironments(), hasSize(1));
        Environment created = system.getEnvironments().get(0);
        assertThat(created.getAddress(), equalTo("http://example.com/service"));
        assertThat(created.getProperties(), nullValue());
    }

    @Test
    @DisplayName("AsyncAPI: a Kafka system persists an environment with the Kafka defaults")
    void asyncApiKafkaPersistsKafkaDefaults() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL, OperationProtocol.KAFKA);
        List<ParsedEnvironment> parsed =
                List.of(ParsedEnvironmentImpl.builder().name("broker").address("kafka:9092").build());

        service.resolveEnvironments(parsed, system, OperationProtocol.KAFKA, messageHandler);

        assertThat(system.getEnvironments(), hasSize(1));
        JsonNode expected = objectMapper.convertValue(
                EnvironmentDefaultParameters.KAFKA_ENVIRONMENT_PARAMETERS, JsonNode.class);
        assertThat(system.getEnvironments().get(0).getProperties(), equalTo(expected));
    }

    @Test
    @DisplayName("AsyncAPI: an AMQP system persists an environment with the Rabbit defaults")
    void asyncApiAmqpPersistsRabbitDefaults() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL, OperationProtocol.AMQP);
        List<ParsedEnvironment> parsed =
                List.of(ParsedEnvironmentImpl.builder().name("broker").address("amqp://host:5672").build());

        service.resolveEnvironments(parsed, system, OperationProtocol.AMQP, messageHandler);

        assertThat(system.getEnvironments(), hasSize(1));
        JsonNode expected = objectMapper.convertValue(
                EnvironmentDefaultParameters.RABBIT_ENVIRONMENT_PARAMETERS, JsonNode.class);
        assertThat(system.getEnvironments().get(0).getProperties(), equalTo(expected));
    }

    @Test
    @DisplayName("Swagger: an EXTERNAL HTTP system persists an environment with the HTTP defaults")
    void swaggerPersistsHttpDefaults() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL, OperationProtocol.HTTP);
        SpecificationGroup specificationGroup = SpecificationGroup.builder()
                .id("group-1")
                .name("group")
                .system(system)
                .build();

        service.resolveSwaggerEnvironments(
                List.of(ParsedEnvironmentImpl.builder().name("prod").address("http://example.com").build()),
                specificationGroup);

        assertThat(system.getEnvironments(), hasSize(1));
        JsonNode expected = objectMapper.convertValue(
                EnvironmentDefaultParameters.HTTP_ENVIRONMENT_PARAMETERS, JsonNode.class);
        assertThat(system.getEnvironments().get(0).getProperties(), equalTo(expected));
    }
}
