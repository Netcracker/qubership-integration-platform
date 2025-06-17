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

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.MapperInterpretatorHelper;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementCreationException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementTransferException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ElementValidationException;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.CreateElementRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.TransferElementRequest;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.testutils.TestElementUtils;
import org.qubership.integration.platform.runtime.catalog.testutils.configuration.TestConfig;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.util.OldContainerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.*;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.any;

@DisplayName("Transferable element service test")
@ContextConfiguration(
        classes = {
                TestConfig.class,
                DescriptorPropertiesConfiguration.class,
                LibraryElementsService.class,
                LibraryResourceLoader.class,
                AuditingHandler.class,
                OrderedElementService.class,
                ElementUtils.class,
                OldContainerUtils.class,
                ElementService.class,
                DependencyService.class,
                SwimlaneService.class,
                TransferableElementService.class
        }
)
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class TransferableElementServiceTest {

    private static final UUID UUID_VALUE = UUID.fromString("94cd4647-46ce-4d94-9123-732068a508ae");

    private static MockedStatic<UUID> mockedUUID;

    @MockBean
    ElementRepository elementRepository;
    @MockBean
    DependencyRepository dependencyRepository;
    @MockBean
    ChainFinderService chainFinderService;
    @MockBean
    ActionsLogService actionsLogService;
    @MockBean
    AuditingHandler jpaAuditingHandler;
    @MockBean
    EnvironmentService environmentService;
    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;
    @MockBean
    SystemEnvironmentsGenerator systemEnvironmentsGenerator;
    @MockBean
    private ChainRepository chainRepository;

    @Autowired
    private LibraryElementsService libraryService;
    @Autowired
    private TransferableElementService transferableElementService;
    private final Chain testChain = Chain.builder().id(TestElementUtils.CHAIN_ID).build();

    @BeforeAll
    public static void initializeBeforeAll() {
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        mockedUUID.when(UUID::randomUUID).thenReturn(UUID_VALUE);
    }

    @AfterAll
    public static void finalizeBeforeAll() {
        mockedUUID.close();
    }

    @DisplayName("Creating chain element and input dependency")
    @Test
    public void createElementAndDependencyTest() {
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_SENDER_TYPE);
        ChainElement triggerElement = createChainElement(TestElementUtils.TEST_TRIGGER_TYPE, TestElementUtils.TRIGGER_ID);
        when(elementRepository.findByIdAndChainId(eq(TestElementUtils.TRIGGER_ID), eq(TestElementUtils.CHAIN_ID))).thenReturn(triggerElement);
        when(chainFinderService.findById(eq(TestElementUtils.CHAIN_ID))).thenReturn(testChain);
        when(elementRepository.save(ArgumentMatchers.any(ChainElement.class))).thenAnswer(i -> i.getArguments()[0]);
        when(dependencyRepository.findByFromAndTo(eq(triggerElement.getId()), eq(UUID_VALUE.toString())))
                .thenReturn(Optional.empty());
        when(dependencyRepository.save(any(Dependency.class))).thenAnswer(i -> i.getArguments()[0]);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_SENDER_TYPE)
                .parentElementId(TestElementUtils.TRIGGER_ID)
                .build();
        ChainDiff chainDiff = transferableElementService.create(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), hasSize(1));
        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());

        ChainElement actualElement = chainDiff.getCreatedElements().get(0);
        Dependency actualDependency = chainDiff.getCreatedDependencies().get(0);

        assertThat(actualElement.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actualElement.getType(), equalTo(TestElementUtils.TEST_SENDER_TYPE));
        assertThat(actualElement.getName(), equalTo(elementDescriptor.getTitle()));
        assertThat(actualElement.getInputDependencies(), hasSize(1));
        assertThat(actualElement.getInputDependencies(), hasItem(actualDependency));
        assertThat(actualElement.getOutputDependencies(), empty());
        assertThat(actualElement.getChain(), equalTo(testChain));
        assertThat(actualElement.getParent(), is(nullValue()));
        assertThat(actualDependency.getElementFrom(), equalTo(triggerElement));
        assertThat(actualDependency.getElementTo(), equalTo(actualElement));
        verify(elementRepository, times(1)).save(eq(actualElement));
        verify(dependencyRepository, times(1)).save(eq(actualDependency));
    }

    @DisplayName("Creating chain element with parent container")
    @Test
    public void createElementWithParentContainerTest() {
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_SENDER_TYPE);
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        when(elementRepository.findByIdAndChainId(eq(TestElementUtils.CASE_1_ID), eq(TestElementUtils.CHAIN_ID))).thenReturn(caseElement);
        when(jpaAuditingHandler.markModified(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(argThat(element -> TestElementUtils.TEST_SENDER_TYPE.equals(element.getType()))))
                .thenAnswer(i -> i.getArguments()[0]);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_SENDER_TYPE)
                .parentElementId(TestElementUtils.CASE_1_ID)
                .build();
        ChainDiff chainDiff = transferableElementService.create(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), hasItem(caseElement));
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());

        ChainElement actualElement = chainDiff.getCreatedElements().get(0);

        assertThat(actualElement.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actualElement.getName(), equalTo(elementDescriptor.getTitle()));
        assertThat(actualElement.getType(), equalTo(TestElementUtils.TEST_SENDER_TYPE));
        assertThat(actualElement.getParent(), equalTo(caseElement));
        assertThat(actualElement.getInputDependencies(), empty());
        assertThat(actualElement.getOutputDependencies(), empty());
        assertThat(actualElement.getChain(), equalTo(testChain));
        verify(jpaAuditingHandler, times(1)).markModified(eq(caseElement));
        verify(elementRepository, times(1)).save(eq(actualElement));
        verify(elementRepository, times(1)).save(eq(caseElement));
    }

    @DisplayName("Creating chain element with no parent")
    @Test
    public void createElementWithNoParentTest() {
        ElementDescriptor elementDescriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_SENDER_TYPE);
        when(chainFinderService.findById(eq(TestElementUtils.CHAIN_ID))).thenReturn(testChain);
        when(elementRepository.save(argThat(element -> TestElementUtils.TEST_SENDER_TYPE.equals(element.getType()))))
                .thenAnswer(i -> i.getArguments()[0]);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_SENDER_TYPE)
                .build();
        ChainDiff chainDiff = transferableElementService.create(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());

        ChainElement actualElement = chainDiff.getCreatedElements().get(0);

        assertThat(actualElement.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actualElement.getName(), equalTo(elementDescriptor.getTitle()));
        assertThat(actualElement.getType(), equalTo(TestElementUtils.TEST_SENDER_TYPE));
        assertThat(actualElement.getParent(), is(nullValue()));
        assertThat(actualElement.getInputDependencies(), empty());
        assertThat(actualElement.getOutputDependencies(), empty());
        assertThat(actualElement.getChain(), equalTo(testChain));
        verify(elementRepository, times(1)).save(eq(actualElement));
    }

    @DisplayName("Creating chain element with non-existent parent")
    @Test
    public void createElementWithNonExistentParentTest() {
        when(elementRepository.findByIdAndChainId(eq(TestElementUtils.CASE_1_ID), eq(TestElementUtils.CHAIN_ID))).thenReturn(null);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_SENDER_TYPE)
                .parentElementId(TestElementUtils.CASE_1_ID)
                .build();
        assertThrows(ElementCreationException.class, () -> transferableElementService.create(TestElementUtils.CHAIN_ID, request));
    }

    @DisplayName("Transferring chain elements")
    @Test
    public void transferElementsTest() {
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ContainerChainElement switchElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        Dependency switchToSenderDependency = Dependency.of(switchElement, senderElement);
        switchToSenderDependency.setId("bd042300-9402-4b6e-8110-62f2e4e09bc1");
        switchElement.addOutputDependency(switchToSenderDependency);
        senderElement.addInputDependency(switchToSenderDependency);
        caseElement.addChildrenElements(Arrays.asList(switchElement, senderElement));

        ContainerChainElement defaultElement = createContainerElement(TestElementUtils.TEST_DEFAULT_TYPE, TestElementUtils.DEFAULT_ID);
        when(jpaAuditingHandler.markModified(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.findById(eq(defaultElement.getId()))).thenReturn(Optional.of(defaultElement));
        when(elementRepository.findAllById(eq(Arrays.asList(switchElement.getId(), senderElement.getId()))))
                .thenReturn(Arrays.asList(switchElement, senderElement));
        when(elementRepository.saveAll(eq(Arrays.asList(switchElement, senderElement)))).thenAnswer(i -> i.getArguments()[0]);

        TransferElementRequest request = TransferElementRequest.builder()
                .parentId(defaultElement.getId())
                .elements(Arrays.asList(switchElement.getId(), senderElement.getId()))
                .build();
        ChainDiff chainDiff = transferableElementService.transfer(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getUpdatedElements(), hasSize(2));
        assertThat(chainDiff.getUpdatedElements(), hasItems(caseElement, defaultElement));
        assertThat(caseElement.getElements(), empty());
        assertThat(switchElement.getParent(), equalTo(defaultElement));
        assertThat(senderElement.getParent(), equalTo(defaultElement));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(elementRepository, times(1)).saveAll(eq(Arrays.asList(switchElement, senderElement)));
    }

    @DisplayName("Transferring ordered chain elements")
    @Test
    public void transferOrderedElementsTest() {
        ElementDescriptor caseDescriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_CASE_TYPE);
        ContainerChainElement firstSwitchElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        ContainerChainElement firstCaseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ContainerChainElement secondCaseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_2_ID);
        firstCaseElement.getProperties().put(caseDescriptor.getPriorityProperty(), 0);
        secondCaseElement.getProperties().put(caseDescriptor.getPriorityProperty(), 1);
        firstSwitchElement.addChildrenElements(Arrays.asList(firstCaseElement, secondCaseElement));

        ContainerChainElement secondSwitchElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_2_ID);
        ContainerChainElement thirdCaseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_3_ID);
        thirdCaseElement.getProperties().put(caseDescriptor.getPriorityProperty(), 0);
        secondSwitchElement.addChildElement(thirdCaseElement);

        when(elementRepository.findById(eq(secondSwitchElement.getId()))).thenReturn(Optional.of(secondSwitchElement));
        when(elementRepository.findAllById(eq(List.of(firstCaseElement.getId()))))
                .thenReturn(Collections.singletonList(firstCaseElement));
        when(jpaAuditingHandler.markModified(eq(secondCaseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.saveAll(eq(List.of(secondCaseElement)))).thenAnswer(i -> i.getArguments()[0]);
        when(jpaAuditingHandler.markModified(eq(firstSwitchElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(firstSwitchElement))).thenAnswer(i -> i.getArguments()[0]);
        when(jpaAuditingHandler.markModified(eq(firstCaseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.saveAll(eq(List.of(firstCaseElement)))).thenAnswer(i -> i.getArguments()[0]);

        TransferElementRequest request = TransferElementRequest.builder()
                .parentId(secondSwitchElement.getId())
                .elements(List.of(firstCaseElement.getId()))
                .build();
        ChainDiff chainDiff = transferableElementService.transfer(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getUpdatedElements(), hasSize(3));
        assertThat(chainDiff.getUpdatedElements(), hasItems(secondCaseElement, firstSwitchElement, secondSwitchElement));
        assertThat(firstCaseElement.getParent(), equalTo(secondSwitchElement));
        assertThat(firstSwitchElement.getElements(), hasSize(1));
        assertThat(firstSwitchElement.getElements(), hasItem(secondCaseElement));
        assertThat(firstCaseElement.getProperty(caseDescriptor.getPriorityProperty()), equalTo(1));
        assertThat(secondCaseElement.getProperty(caseDescriptor.getPriorityProperty()), equalTo(0));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(jpaAuditingHandler, times(1)).markModified(eq(firstCaseElement));
        verify(jpaAuditingHandler, times(1)).markModified(eq(secondCaseElement));
        verify(jpaAuditingHandler, times(1)).markModified(eq(firstSwitchElement));
        verify(elementRepository, times(1)).saveAll(List.of(secondCaseElement));
        verify(elementRepository, times(1)).save(eq(firstSwitchElement));
        verify(elementRepository, times(1)).saveAll(eq(List.of(firstCaseElement)));
    }

    @DisplayName("Transferring chain elements to null parent")
    @Test
    public void transferElementsToNullParentTest() {
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        caseElement.addChildElement(senderElement);
        when(elementRepository.findAllById(eq(List.of(senderElement.getId())))).thenReturn(List.of(senderElement));
        when(jpaAuditingHandler.markModified(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(jpaAuditingHandler.markModified(eq(senderElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.saveAll(eq(List.of(senderElement)))).thenAnswer(i -> i.getArguments()[0]);

        TransferElementRequest request = TransferElementRequest.builder()
                .elements(List.of(senderElement.getId()))
                .build();
        ChainDiff chainDiff = transferableElementService.transfer(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getUpdatedElements(), hasSize(2));
        assertThat(chainDiff.getUpdatedElements(), hasItems(senderElement, caseElement));
        assertThat(senderElement.getParent(), is(nullValue()));
        assertThat(caseElement.getElements(), empty());
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(jpaAuditingHandler, times(1)).markModified(eq(caseElement));
        verify(jpaAuditingHandler, times(1)).markModified(eq(senderElement));
        verify(elementRepository, times(1)).saveAll(eq(List.of(senderElement)));
    }

    @DisplayName("Transferring chain elements from null to parent")
    @Test
    public void transferElementsFromNullToParentTest() {
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        when(elementRepository.findById(eq(caseElement.getId()))).thenReturn(Optional.of(caseElement));
        when(elementRepository.findAllById(eq(List.of(senderElement.getId())))).thenReturn(List.of(senderElement));
        when(jpaAuditingHandler.markModified(eq(senderElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.saveAll(eq(List.of(senderElement)))).thenAnswer(i -> i.getArguments()[0]);

        TransferElementRequest request = TransferElementRequest.builder()
                .parentId(caseElement.getId())
                .elements(List.of(senderElement.getId()))
                .build();
        ChainDiff chainDiff = transferableElementService.transfer(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getUpdatedElements(), hasSize(2));
        assertThat(chainDiff.getUpdatedElements(), hasItems(senderElement, caseElement));
        assertThat(senderElement.getParent(), equalTo(caseElement));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(jpaAuditingHandler, times(1)).markModified(eq(senderElement));
        verify(elementRepository, times(1)).saveAll(eq(List.of(senderElement)));
    }

    @DisplayName("Transferring element to restricted container")
    @Test
    public void transferElementToRestrictedContainerTest() {
        ChainElement senderElement = ChainElement.builder().id(TestElementUtils.SENDER_1_ID).type(TestElementUtils.TEST_SENDER_TYPE).build();
        ContainerChainElement switchElement = ContainerChainElement.builder().id(TestElementUtils.SWITCH_1_ID).type(TestElementUtils.TEST_SWITCH_TYPE).build();
        when(elementRepository.findById(eq(switchElement.getId()))).thenReturn(Optional.of(switchElement));
        when(elementRepository.findAllById(eq(List.of(senderElement.getId())))).thenReturn(List.of(senderElement));
        when(dependencyRepository.findByFromAndTo(eq(senderElement.getId()), eq(switchElement.getId()))).thenReturn(Optional.empty());
        when(dependencyRepository.save(any(Dependency.class))).thenAnswer(i -> i.getArguments()[0]);

        TransferElementRequest request = TransferElementRequest.builder()
                .parentId(TestElementUtils.SWITCH_1_ID)
                .elements(List.of(TestElementUtils.SENDER_1_ID))
                .build();
        ChainDiff chainDiff = transferableElementService.transfer(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getRemovedDependencies(), empty());

        Dependency dependency = chainDiff.getCreatedDependencies().get(0);

        assertThat(dependency.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(dependency.getElementFrom(), equalTo(switchElement));
        assertThat(dependency.getElementTo(), equalTo(senderElement));
        assertThat(switchElement.getInputDependencies(), empty());
        assertThat(switchElement.getOutputDependencies(), hasSize(1));
        assertThat(switchElement.getOutputDependencies(), Matchers.contains(dependency));
        assertThat(senderElement.getInputDependencies(), hasSize(1));
        assertThat(senderElement.getOutputDependencies(), empty());
        assertThat(senderElement.getInputDependencies(), Matchers.contains(dependency));
        verify(dependencyRepository, times(1)).save(eq(dependency));
    }

    private static Stream<Arguments> transferElementsToInvalidParentTestData() {
        return Stream.of(
                Arguments.of("Non-existent parent", null)
        );
    }

    @DisplayName("Transferring chain elements to invalid parent")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("transferElementsToInvalidParentTestData")
    public void transferElementsToInvalidParentTest(String scenario, ChainElement parentElement) {
        when(elementRepository.findById(eq(TestElementUtils.CONTAINER_ID))).thenReturn(Optional.ofNullable(parentElement));

        TransferElementRequest request = TransferElementRequest.builder()
                .parentId(TestElementUtils.CONTAINER_ID)
                .elements(List.of(TestElementUtils.SENDER_1_ID))
                .build();
        assertThrows(ElementTransferException.class, () -> transferableElementService.transfer(TestElementUtils.CHAIN_ID, request));
    }

    private static Stream<Arguments> transferElementsWithInvalidParentRestrictionsTestData() {
        return Stream.of(
                Arguments.of(
                        "Unknown chain element",
                        ChainElement.builder().type("unknown").build(),
                        ContainerChainElement.builder().id(TestElementUtils.CONTAINER_ID).type(TestElementUtils.TEST_CONTAINER_TYPE).build(),
                        ElementTransferException.class
                ),
                Arguments.of(
                        "Blank parent type",
                        ChainElement.builder().type("   ").build(),
                        ContainerChainElement.builder().id(TestElementUtils.CONTAINER_ID).type(TestElementUtils.TEST_CONTAINER_TYPE).build(),
                        ElementTransferException.class
                ),
                Arguments.of(
                        "Invalid parent type",
                        ContainerChainElement.builder().id(TestElementUtils.CASE_1_ID).type(TestElementUtils.TEST_CASE_TYPE).build(),
                        ContainerChainElement.builder().id(TestElementUtils.DEFAULT_ID).type(TestElementUtils.TEST_DEFAULT_TYPE).build(),
                        ElementValidationException.class
                ),
                Arguments.of(
                        "Element with disabled input",
                        ChainElement.builder().type(TestElementUtils.TEST_TRIGGER_TYPE).build(),
                        ContainerChainElement.builder().id(TestElementUtils.CASE_1_ID).type(TestElementUtils.TEST_CASE_TYPE).build(),
                        ElementValidationException.class
                ),
                Arguments.of(
                        "Invalid quantity",
                        ContainerChainElement.builder().id(TestElementUtils.DEFAULT_ID).type(TestElementUtils.TEST_DEFAULT_TYPE).build(),
                        ContainerChainElement.builder()
                                .id(TestElementUtils.SWITCH_1_ID)
                                .type(TestElementUtils.TEST_SWITCH_TYPE)
                                .elements(List.of(ContainerChainElement.builder().type(TestElementUtils.TEST_DEFAULT_TYPE).build()))
                                .build(),
                        ElementValidationException.class
                )
        );
    }

    @DisplayName("Transferring chain elements with invalid parent restrictions")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("transferElementsWithInvalidParentRestrictionsTestData")
    public void transferElementsWithInvalidParentRestrictionsTest(
            String scenario,
            ChainElement elementToTransfer,
            ChainElement parentElement,
            Class<Throwable> expectedException
    ) {
        when(elementRepository.findById(eq(parentElement.getId()))).thenReturn(Optional.of(parentElement));
        when(elementRepository.findAllById(eq(List.of(elementToTransfer.getId())))).thenReturn(List.of(elementToTransfer));

        TransferElementRequest request = TransferElementRequest.builder()
                .parentId(parentElement.getId())
                .elements(List.of(elementToTransfer.getId()))
                .build();
        assertThrows(expectedException, () -> transferableElementService.transfer(TestElementUtils.CHAIN_ID, request));
    }

    private ContainerChainElement createContainerElement(String type, String id) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(type);
        return ContainerChainElement.builder()
                .id(id)
                .type(descriptor.getName())
                .name(descriptor.getTitle())
                .chain(testChain)
                .build();
    }

    private ChainElement createChainElement(String type, String id) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(type);
        return ChainElement.builder()
                .id(id)
                .type(descriptor.getName())
                .name(descriptor.getTitle())
                .chain(testChain)
                .build();
    }
}
