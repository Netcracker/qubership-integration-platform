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

package org.qubership.integration.platform.runtime.catalog.service.parsers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParser;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedEnvironmentImpl;
import org.qubership.integration.platform.parsers.model.ParsedOperationImpl;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link OperationParserService#parse} branches that the identity test does not: the missing
 * parser and parser-error translation, the per-protocol environment dispatch, generated versioning,
 * and operation-id de-duplication. The identity test owns the merge invariant and the two duplicate
 * checks.
 */
@ExtendWith(MockitoExtension.class)
class OperationParserServiceTest {

    private static final String GROUP_ID = "group-1";
    private static final String DECLARED_VERSION = "1.0.0";

    @Mock
    private OperationRepository operationRepository;
    @Mock
    private SystemModelRepository systemModelRepository;
    @Mock
    private SpecificationGroupRepository specificationGroupRepository;
    @Mock
    private SpecificationSourceRepository specificationSourceRepository;
    @Mock
    private SystemModelBaseService systemModelBaseService;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private EnvironmentBaseService environmentBaseService;

    private final TransactionHandler transactionHandler = new TransactionHandler();
    private final List<String> messages = new ArrayList<>();

    private SpecificationGroup specificationGroup;
    private IntegrationSystem system;

    @BeforeEach
    void setUp() {
        system = IntegrationSystem.builder().id("system-1").protocol(OperationProtocol.AMQP).build();
        specificationGroup = SpecificationGroup.builder().id(GROUP_ID).build();
        specificationGroup.setName("orders");
        specificationGroup.setSystem(system);
    }

    private OperationParserService serviceWith(SpecificationParser... parsers) {
        return new OperationParserService(
                List.of(parsers),
                operationRepository,
                systemModelRepository,
                specificationGroupRepository,
                specificationSourceRepository,
                systemModelBaseService,
                actionLogger,
                transactionHandler,
                environmentBaseService);
    }

    /** Lets the parse flow reach the end: the group is resolvable, no version clashes, saves echo. */
    private void stubHappyPersistence() {
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(specificationGroup);
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(eq(GROUP_ID), any())).thenReturn(0L);
        when(systemModelRepository.save(any(SystemModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(specificationSourceRepository.saveAll(anyIterable())).thenReturn(Collections.emptyList());
    }

    private SystemModel parse(SpecificationParser parser, String parserName) throws Exception {
        return serviceWith(parser).parse(parserName, GROUP_ID, List.of(), false, Set.of(), messages::add).get();
    }

    @Test
    void parseFailsWhenNoParserIsRegisteredForTheProtocol() {
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(specificationGroup);
        OperationParserService service = serviceWith(new SwaggerParser(ParsedSystemModelImpl.builder().build()));

        assertThatThrownBy(() ->
                service.parse("unknown", GROUP_ID, List.of(), false, Set.of(), messages::add).get())
                .cause()
                .isInstanceOf(SpecificationImportException.class)
                .hasMessageContaining("No specification parser is registered for protocol 'unknown'");
        assertThat(specificationGroup.getSystemModels()).isEmpty();
    }

    @Test
    void parseTranslatesLibraryParserExceptionToImportException() {
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(specificationGroup);
        OperationParserService service =
                serviceWith(new SwaggerParser(new SpecificationParserException("malformed schema")));

        assertThatThrownBy(() ->
                service.parse("swagger", GROUP_ID, List.of(), false, Set.of(), messages::add).get())
                .cause()
                .isInstanceOf(SpecificationImportException.class)
                .hasMessageContaining("malformed schema");
    }

    @Test
    void parseDispatchesSwaggerEnvironmentsToTheSwaggerResolver() throws Exception {
        stubHappyPersistence();
        parse(new SwaggerParser(modelWithOneEnvironment()), "swagger");

        verify(environmentBaseService).resolveSwaggerEnvironments(any(), eq(specificationGroup));
        verify(environmentBaseService, never()).resolveWsdlEnvironments(any(), any(), any());
        verify(environmentBaseService, never()).resolveEnvironments(any(), any(), any(), any());
    }

    @Test
    void parseDispatchesSoapEnvironmentsToTheWsdlResolver() throws Exception {
        stubHappyPersistence();
        parse(new SoapParser(modelWithOneEnvironment()), "soap");

        verify(environmentBaseService).resolveWsdlEnvironments(any(), eq(system), any());
        verify(environmentBaseService, never()).resolveSwaggerEnvironments(any(), any());
        verify(environmentBaseService, never()).resolveEnvironments(any(), any(), any(), any());
    }

    @Test
    void parseDispatchesAsyncapiEnvironmentsToTheSharedResolver() throws Exception {
        stubHappyPersistence();
        parse(new AsyncapiParser(modelWithOneEnvironment()), "asyncapi");

        verify(environmentBaseService).resolveEnvironments(any(), eq(system), eq(OperationProtocol.AMQP), any());
        verify(environmentBaseService, never()).resolveSwaggerEnvironments(any(), any());
        verify(environmentBaseService, never()).resolveWsdlEnvironments(any(), any(), any());
    }

    @Test
    void parseReconcilesNoEnvironmentsForGraphqlAndGrpc() throws Exception {
        stubHappyPersistence();
        parse(new SchemaParser(modelWithOneEnvironment()), "graphqlschema");

        verify(environmentBaseService, never()).resolveSwaggerEnvironments(any(), any());
        verify(environmentBaseService, never()).resolveWsdlEnvironments(any(), any(), any());
        verify(environmentBaseService, never()).resolveEnvironments(any(), any(), any(), any());
    }

    @Test
    void parseGeneratesSequentialVersionWhenNoneIsDeclared() throws Exception {
        stubHappyPersistence();
        when(systemModelBaseService.getSystemModelsBySpecificationGroupId(GROUP_ID))
                .thenReturn(List.of(SystemModel.builder().build(), SystemModel.builder().build()));

        SystemModel result = parse(new SchemaParser(ParsedSystemModelImpl.builder().version(null).build()), "graphqlschema");

        assertThat(result.getVersion()).isEqualTo("3.0.0");
        assertThat(result.getName()).isEqualTo("3.0.0");
    }

    @Test
    void parseGivesDuplicateOperationNamesDistinctIdsAndReportsThem() throws Exception {
        stubHappyPersistence();
        ParsedSystemModel model = ParsedSystemModelImpl.builder()
                .version(DECLARED_VERSION)
                .operations(new ArrayList<>(List.of(
                        ParsedOperationImpl.builder().name("getPet").build(),
                        ParsedOperationImpl.builder().name("getPet").build())))
                .build();

        SystemModel result = parse(new SchemaParser(model), "graphqlschema");

        List<String> operationIds = result.getOperations().stream().map(Operation::getId).toList();
        String base = GROUP_ID + "-" + DECLARED_VERSION + "-getPet";
        assertThat(operationIds).containsExactly(base, base + "-1");
        assertThat(messages).anyMatch(message -> message.contains("Duplicated operation identifier: getPet"));
    }

    private static ParsedSystemModel modelWithOneEnvironment() {
        return ParsedSystemModelImpl.builder()
                .version(DECLARED_VERSION)
                .environments(new ArrayList<>(List.of(
                        ParsedEnvironmentImpl.builder().name("prod").address("http://prod").build())))
                .build();
    }

    /**
     * Base for the protocol-specific stub parsers. The {@code @Parser} protocol lives on each
     * subclass because {@code OperationParserService} keys the parsers by the annotation on their
     * concrete class; the returned model (or the failure to raise) is passed per instance.
     */
    private abstract static class StubParser implements SpecificationParser {
        private final ParsedSystemModel model;
        private final RuntimeException failure;

        StubParser(ParsedSystemModel model, RuntimeException failure) {
            this.model = model;
            this.failure = failure;
        }

        @Override
        public ParsedSystemModel parseSpecification(String groupId,
                                                    Collection<SpecificationSource> sources,
                                                    Consumer<String> messageHandler) {
            if (failure != null) {
                throw failure;
            }
            return model;
        }
    }

    @Parser("swagger")
    private static final class SwaggerParser extends StubParser {
        SwaggerParser(ParsedSystemModel model) {
            super(model, null);
        }

        SwaggerParser(RuntimeException failure) {
            super(null, failure);
        }
    }

    @Parser("soap")
    private static final class SoapParser extends StubParser {
        SoapParser(ParsedSystemModel model) {
            super(model, null);
        }
    }

    @Parser("asyncapi")
    private static final class AsyncapiParser extends StubParser {
        AsyncapiParser(ParsedSystemModel model) {
            super(model, null);
        }
    }

    @Parser("graphqlschema")
    private static final class SchemaParser extends StubParser {
        SchemaParser(ParsedSystemModel model) {
            super(model, null);
        }
    }
}
