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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedEnvironmentImpl;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.EnvironmentRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests that pin the environment reconcile behavior of
 * {@link EnvironmentBaseService#resolveEnvironments(List, IntegrationSystem, OperationProtocol, Consumer)}.
 * This is the shared path every library parser feeds, AsyncAPI included: each parser maps its
 * servers or endpoints to {@link ParsedEnvironment} values, and the catalog reconciles them here.
 * The branches covered here must behave identically before and after the parsing extraction.
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentBaseServiceTest {

    private static final String ENV_ADDRESS = "http://host:8080";
    private static final String ENV_NAME = "env-1";

    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private SystemBaseService systemBaseService;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private ParserUtils parserUtils;

    private EnvironmentBaseService service;
    private final List<String> messages = new ArrayList<>();
    private final Consumer<String> messageHandler = messages::add;

    @BeforeEach
    void setUp() {
        service = new EnvironmentBaseService(
                environmentRepository, systemBaseService, actionLogger, new ObjectMapper(), parserUtils);
    }

    private IntegrationSystem system(IntegrationSystemType type) {
        return IntegrationSystem.builder()
                .id("system-1")
                .name("test system")
                .integrationSystemType(type)
                .protocol(OperationProtocol.HTTP)
                .build();
    }

    private List<ParsedEnvironment> singleEnvironment() {
        return List.of(ParsedEnvironmentImpl.builder().name(ENV_NAME).address(ENV_ADDRESS).build());
    }

    @Test
    @DisplayName("EXTERNAL system with a new environment creates it")
    void externalWithNewEnvironmentCreates() {
        JsonNode emptyProperties = JsonNodeFactory.instance.objectNode();
        when(parserUtils.receiveEmptyProperties(OperationProtocol.HTTP)).thenReturn(emptyProperties);
        when(environmentRepository.save(any(Environment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);

        service.resolveEnvironments(singleEnvironment(), system, OperationProtocol.HTTP, messageHandler);

        verify(environmentRepository).save(any(Environment.class));
        assertThat(system.getEnvironments(), hasSize(1));
        Environment created = system.getEnvironments().get(0);
        assertThat(created.getName(), equalTo(ENV_NAME));
        assertThat(created.getAddress(), equalTo(ENV_ADDRESS));
        assertThat(created.getSourceType(), equalTo(EnvironmentSourceType.MANUAL));
        assertThat(created.getProperties(), sameInstance(emptyProperties));
    }

    @Test
    @DisplayName("EXTERNAL system skips an environment that matches an existing one")
    void externalDedupsIdenticalEnvironment() {
        JsonNode emptyProperties = JsonNodeFactory.instance.objectNode();
        when(parserUtils.receiveEmptyProperties(OperationProtocol.HTTP)).thenReturn(emptyProperties);

        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);
        Environment existing = Environment.builder()
                .name(ENV_NAME)
                .address(ENV_ADDRESS)
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .properties(emptyProperties)
                .build();
        system.addEnvironment(existing);

        service.resolveEnvironments(singleEnvironment(), system, OperationProtocol.HTTP, messageHandler);

        verify(environmentRepository, never()).save(any(Environment.class));
        assertThat(system.getEnvironments(), contains(existing));
    }

    @Test
    @DisplayName("INTERNAL system with no environments creates one from the first environment")
    void internalWithEmptyEnvironmentsCreates() {
        JsonNode emptyProperties = JsonNodeFactory.instance.objectNode();
        when(parserUtils.receiveEmptyProperties(OperationProtocol.HTTP)).thenReturn(emptyProperties);
        when(environmentRepository.save(any(Environment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationSystem system = system(IntegrationSystemType.INTERNAL);

        service.resolveEnvironments(singleEnvironment(), system, OperationProtocol.HTTP, messageHandler);

        verify(environmentRepository).save(any(Environment.class));
        assertThat(system.getEnvironments(), hasSize(1));
        assertThat(system.getEnvironments().get(0).getName(), equalTo(ENV_NAME));
    }

    @Test
    @DisplayName("INTERNAL system replaces a MANUAL blank-address environment")
    void internalReplacesBlankManualEnvironment() {
        JsonNode emptyProperties = JsonNodeFactory.instance.objectNode();
        when(parserUtils.receiveEmptyProperties(OperationProtocol.HTTP)).thenReturn(emptyProperties);
        when(environmentRepository.save(any(Environment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationSystem system = system(IntegrationSystemType.INTERNAL);
        Environment placeholder = Environment.builder()
                .id("old-1")
                .name("placeholder")
                .address("")
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .build();
        system.addEnvironment(placeholder);

        service.resolveEnvironments(singleEnvironment(), system, OperationProtocol.HTTP, messageHandler);

        verify(environmentRepository).delete(placeholder);
        verify(environmentRepository).save(any(Environment.class));
        assertThat(system.getEnvironments(), hasSize(1));
        assertThat(system.getEnvironments().get(0).getName(), equalTo(ENV_NAME));
        assertThat(system.getEnvironments().get(0).getAddress(), equalTo(ENV_ADDRESS));
    }

    @Test
    @DisplayName("No environments on an INTERNAL system set empty properties and report the message")
    void emptyEnvironmentsOnInternalSetEmptyPropertiesAndReportMessage() {
        JsonNode emptyProperties = JsonNodeFactory.instance.objectNode();
        when(parserUtils.receiveEmptyProperties(OperationProtocol.HTTP)).thenReturn(emptyProperties);

        IntegrationSystem system = system(IntegrationSystemType.INTERNAL);
        Environment existing = Environment.builder()
                .id("e1")
                .name("existing")
                .address(ENV_ADDRESS)
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .properties(null)
                .build();
        system.addEnvironment(existing);

        service.resolveEnvironments(List.of(), system, OperationProtocol.HTTP, messageHandler);

        verify(environmentRepository, never()).save(any(Environment.class));
        assertThat(existing.getProperties(), sameInstance(emptyProperties));
        assertThat(messages, contains(EnvironmentBaseService.SPECIFICATION_PARAMETERS_ARE_EMPTY_MESSAGE));
    }

    @Test
    @DisplayName("No environments on an EXTERNAL system report the message and create nothing")
    void emptyEnvironmentsOnExternalReportMessage() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);

        service.resolveEnvironments(List.of(), system, OperationProtocol.HTTP, messageHandler);

        verify(environmentRepository, never()).save(any(Environment.class));
        assertThat(system.getEnvironments(), empty());
        assertThat(messages, contains(EnvironmentBaseService.SPECIFICATION_PARAMETERS_ARE_EMPTY_MESSAGE));
    }

    @Test
    @DisplayName("WSDL: a non-EXTERNAL system is left untouched")
    void wsdlSkipsNonExternalSystem() {
        IntegrationSystem system = system(IntegrationSystemType.INTERNAL);

        service.resolveWsdlEnvironments(
                List.of(ParsedEnvironmentImpl.builder().name("port").address("http://example.com").build()),
                system, messageHandler);

        verify(environmentRepository, never()).save(any(Environment.class));
        assertThat(system.getEnvironments(), empty());
    }

    @Test
    @DisplayName("WSDL: an EXTERNAL system drops endpoints whose address is not a valid URL")
    void wsdlDropsInvalidEndpointAddresses() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);

        service.resolveWsdlEnvironments(
                List.of(ParsedEnvironmentImpl.builder().name("port").address("${host}/service").build()),
                system, messageHandler);

        verify(environmentRepository, never()).save(any(Environment.class));
        assertThat(system.getEnvironments(), empty());
    }

    @Test
    @DisplayName("WSDL: an EXTERNAL system creates an environment with no default properties")
    void wsdlCreatesEnvironmentForValidEndpoint() {
        when(environmentRepository.save(any(Environment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);

        service.resolveWsdlEnvironments(
                List.of(ParsedEnvironmentImpl.builder().name("port").address("http://example.com/service").build()),
                system, messageHandler);

        verify(environmentRepository).save(any(Environment.class));
        assertThat(system.getEnvironments(), hasSize(1));
        Environment created = system.getEnvironments().get(0);
        assertThat(created.getName(), equalTo("port"));
        assertThat(created.getAddress(), equalTo("http://example.com/service"));
        assertThat(created.getSourceType(), equalTo(EnvironmentSourceType.MANUAL));
        // The pre-extraction WSDL path never set properties; Kafka defaults must not leak in.
        assertThat(created.getProperties(), nullValue());
    }

    @Test
    @DisplayName("WSDL: an EXTERNAL system skips an endpoint that matches an existing environment")
    void wsdlDedupsIdenticalEndpoint() {
        IntegrationSystem system = system(IntegrationSystemType.EXTERNAL);
        Environment existing = Environment.builder()
                .name("port")
                .address("http://example.com/service")
                .labels(new ArrayList<>())
                .sourceType(EnvironmentSourceType.MANUAL)
                .build();
        system.addEnvironment(existing);

        service.resolveWsdlEnvironments(
                List.of(ParsedEnvironmentImpl.builder().name("port").address("http://example.com/service").build()),
                system, messageHandler);

        verify(environmentRepository, never()).save(any(Environment.class));
        assertThat(system.getEnvironments(), contains(existing));
    }
}
