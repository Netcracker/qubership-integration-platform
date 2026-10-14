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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParser;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationGroupRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SpecificationSourceRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

/**
 * Guards that {@link OperationParserService#parse} keeps a single {@link SystemModel} instance per
 * id inside the session, so importing a specification does not fail on flush.
 *
 * <p>{@code SystemModel} carries an assigned id, so {@code SystemModelRepository.save} routes to
 * {@code EntityManager.merge}, which returns a separate managed copy rather than managing the
 * argument. If the model is wired into the group's {@code cascade = ALL} collection before it is
 * saved, that collection keeps the pre-merge instance while the managed copy holds the same id. On
 * flush, Hibernate cascades over the group's collection, finds a second object for the id already
 * associated with the session, and throws {@code NonUniqueObjectException} — the failure a user hit
 * when importing a petstore specification.
 *
 * <p>The test drives the real {@code parse} flow. The fake {@code SystemModelRepository} reproduces
 * merge semantics: the first save of an assigned-id entity returns a fresh managed copy, and a later
 * save of the instance already managed for that id returns that same instance. The assertion checks
 * the invariant the flush cascade needs — the model reachable through the group's collection is the
 * same instance the session manages for its id.
 */
@ExtendWith(MockitoExtension.class)
class OperationParserServiceSystemModelIdentityTest {

    private static final String PARSER_NAME = "identity-reproducer";
    private static final String GROUP_ID = "89cc710d-fde6-4b36-8605-c1464fa896cc";
    private static final String DECLARED_VERSION = "petstore-1.0.7";
    private static final String SYSTEM_MODEL_ID = GROUP_ID + "-" + DECLARED_VERSION;

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
    private final Map<String, SystemModel> persistenceContext = new HashMap<>();

    private OperationParserService service;
    private SpecificationGroup specificationGroup;

    @BeforeEach
    void setUp() {
        specificationGroup = SpecificationGroup.builder().id(GROUP_ID).build();
        specificationGroup.setName("petstore");

        service = new OperationParserService(
                List.of(new DeclaredVersionParser()),
                operationRepository,
                systemModelRepository,
                specificationGroupRepository,
                specificationSourceRepository,
                systemModelBaseService,
                actionLogger,
                transactionHandler,
                environmentBaseService);
    }

    @Test
    @DisplayName("parse keeps one SystemModel instance per id in the group's cascade collection")
    void parseKeepsOneSystemModelInstancePerIdInTheGroupCollection() throws Exception {
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(specificationGroup);
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, DECLARED_VERSION))
                .thenReturn(0L);
        when(specificationSourceRepository.saveAll(anyIterable())).thenReturn(Collections.emptyList());
        modelSpringDataSaveAsMerge();

        CompletableFuture<SystemModel> future = service.parse(
                PARSER_NAME, GROUP_ID, List.of(), false, Set.of(), message -> { });
        SystemModel persisted = future.get();

        List<SystemModel> modelsInGroup = specificationGroup.getSystemModels();
        assertThat(modelsInGroup).hasSize(1);
        assertThat(modelsInGroup.get(0))
                .as("The model reachable through the group's cascade collection must be the same "
                        + "instance the session manages for id %s; a second instance makes the flush "
                        + "cascade throw NonUniqueObjectException.", SYSTEM_MODEL_ID)
                .isSameAs(persisted)
                .isSameAs(persistenceContext.get(SYSTEM_MODEL_ID));
    }

    @Test
    @DisplayName("parse rejects a specification whose declared version already exists")
    void parseRejectsADuplicateDeclaredVersion() {
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(specificationGroup);
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, DECLARED_VERSION))
                .thenReturn(1L);

        CompletableFuture<SystemModel> future = service.parse(
                PARSER_NAME, GROUP_ID, List.of(), false, Set.of(), message -> { });

        assertThatThrownBy(future::get)
                .hasCauseInstanceOf(SpecificationSimilarVersionException.class);
        assertThat(specificationGroup.getSystemModels()).isEmpty();
    }

    @Test
    @DisplayName("parse rejects a specification whose id already exists in the system")
    void parseRejectsADuplicateSystemModelId() {
        when(specificationGroupRepository.getReferenceById(GROUP_ID)).thenReturn(specificationGroup);
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, DECLARED_VERSION))
                .thenReturn(0L);

        CompletableFuture<SystemModel> future = service.parse(
                PARSER_NAME, GROUP_ID, List.of(), false, Set.of(SYSTEM_MODEL_ID), message -> { });

        assertThatThrownBy(future::get)
                .hasCauseInstanceOf(SpecificationSimilarIdException.class);
        assertThat(specificationGroup.getSystemModels()).isEmpty();
    }

    /**
     * Stubs the repository to reproduce Spring Data {@code save} on an assigned-id entity, which
     * delegates to {@code EntityManager.merge}: the first save copies the transient entity into a new
     * managed instance, while a save of the instance already managed for that id returns that same
     * instance.
     */
    private void modelSpringDataSaveAsMerge() {
        when(systemModelRepository.save(any(SystemModel.class))).thenAnswer(invocation -> {
            SystemModel argument = invocation.getArgument(0);
            SystemModel managed = persistenceContext.get(argument.getId());
            if (managed == argument) {
                return managed;
            }
            if (managed == null) {
                managed = mergedCopy(argument);
                persistenceContext.put(argument.getId(), managed);
            }
            return managed;
        });
    }

    private static SystemModel mergedCopy(SystemModel source) {
        SystemModel copy = SystemModel.builder().id(source.getId()).build();
        copy.setName(source.getName());
        copy.setVersion(source.getVersion());
        copy.setDescription(source.getDescription());
        copy.setSource(source.getSource());
        return copy;
    }

    @Parser(PARSER_NAME)
    private static final class DeclaredVersionParser implements SpecificationParser {

        @Override
        public ParsedSystemModel parseSpecification(String groupId,
                                                    Collection<SpecificationSource> sources,
                                                    Consumer<String> messageHandler) {
            return ParsedSystemModelImpl.builder()
                    .description("Pet Store service")
                    .version(DECLARED_VERSION)
                    .build();
        }
    }
}
