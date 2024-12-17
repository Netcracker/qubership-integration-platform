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

package org.qubership.integration.platform.designtime.catalog.service;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.google.common.collect.ImmutableMap;
import org.qubership.integration.platform.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.catalog.util.ElementUtils;
import org.qubership.integration.platform.designtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.designtime.catalog.exception.exceptions.ElementCreationException;
import org.qubership.integration.platform.designtime.catalog.exception.exceptions.ElementValidationException;
import org.qubership.integration.platform.designtime.catalog.rest.v1.dto.element.CreateElementRequest;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.designtime.catalog.testutils.TestElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.qubership.integration.platform.designtime.catalog.service.ElementService.CONTAINER_DEFAULT_NAME;
import static org.qubership.integration.platform.designtime.catalog.service.ElementService.CONTAINER_TYPE_NAME;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.*;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.beans.SamePropertyValuesAs.samePropertyValuesAs;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.collection.IsIn.in;
import static org.hamcrest.collection.IsMapWithSize.aMapWithSize;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Element service test")
@ContextConfiguration(
        classes = {
                ElementServiceTest.TestConfig.class,
                DescriptorPropertiesConfiguration.class,
                LibraryElementsService.class,
                LibraryResourceLoader.class,
                AuditingHandler.class,
                OrderedElementService.class,
                ElementUtils.class,
                SwimlaneService.class,
                ElementService.class
        }
)
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class ElementServiceTest {

    private static final UUID UUID_VALUE = UUID.fromString("458f93d1-d647-4bf0-b076-a0392f15ed8b");

    private static MockedStatic<UUID> mockedUUID;

    @MockBean
    ElementRepository elementRepository;
    @MockBean
    ChainService chainService;
    @MockBean
    ActionsLogService actionsLogService;
    @MockBean
    AuditingHandler jpaAuditingHandler;
    @MockBean
    EnvironmentService environmentService;

    @Autowired
    private LibraryElementsService libraryService;
    @Autowired
    private ElementService elementService;
    private final Chain testChain = Chain.builder().id(TestElementUtils.CHAIN_ID).build();


    @TestConfiguration
    static class TestConfig {

        @Bean
        public YAMLMapper defaultYamlMapper() {
            return new YAMLMapper();
        }
    }


    @BeforeAll
    public static void initializeBeforeAll() {
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        mockedUUID.when(UUID::randomUUID).thenReturn(UUID_VALUE);
    }

    @AfterAll
    public static void finalizeBeforeAll() {
        mockedUUID.close();
    }

    @BeforeEach
    public void initializeBeforeEach() {
        when(chainService.findById(eq(TestElementUtils.CHAIN_ID))).thenReturn(testChain);
    }

    @DisplayName("Creating chain element")
    @Test
    public void createElementTest() {
        when(elementRepository.save(ArgumentMatchers.any(ChainElement.class))).thenAnswer(i -> i.getArguments()[0]);

        ElementDescriptor descriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_SENDER_TYPE);
        Map<String, Object> expectedProperties = extractElementProperties(descriptor);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_SENDER_TYPE)
                .build();
        ChainDiff chainDiff = elementService.create(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());

        ChainElement actual = chainDiff.getCreatedElements().get(0);

        assertThat(actual.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actual.getName(), equalTo(descriptor.getTitle()));
        assertThat(actual.getType(), equalTo(descriptor.getName()));
        assertThat(actual.getChain(), samePropertyValuesAs(testChain));
        assertPropertiesAreEqual(actual.getProperties(), expectedProperties);
        verify(elementRepository, times(1)).save(eq(actual));
    }

    @DisplayName("Creating container chain element")
    @Test
    public void createContainerElementTest() {
        when(elementRepository.save(ArgumentMatchers.any(ChainElement.class))).thenAnswer(i -> i.getArguments()[0]);

        ElementDescriptor descriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_SWITCH_TYPE);
        Map<String, ElementDescriptor> allowedChildren = descriptor.getAllowedChildren().keySet().stream()
                .map(it -> Pair.of(it, libraryService.getElementDescriptor(it)))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
        Map<String, Object> expectedProperties = extractElementProperties(descriptor);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_SWITCH_TYPE)
                .build();
        ChainDiff chainDiff = elementService.create(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());

        ChainElement actual = chainDiff.getCreatedElements().get(0);

        assertThat(actual, instanceOf(ContainerChainElement.class));
        assertThat(actual.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actual.getName(), equalTo(descriptor.getTitle()));
        assertThat(actual.getType(), equalTo(descriptor.getName()));
        assertThat(actual.getChain(), samePropertyValuesAs(testChain));
        assertPropertiesAreEqual(actual.getProperties(), expectedProperties);
        verify(elementRepository, times(4)).save(argThat(element -> element == actual));

        ContainerChainElement actualContainer = (ContainerChainElement) actual;

        assertThat(actualContainer.getElements(), hasSize(allowedChildren.size()));
        for (ChainElement actualChild : actualContainer.getElements()) {
            assertThat(actualChild.getType(), is(in(allowedChildren.keySet())));

            ElementDescriptor childDescriptor = allowedChildren.get(actualChild.getType());
            Map<String, Object> expectedChildProperties = extractElementProperties(childDescriptor);
            if (childDescriptor.isOrdered()) {
                expectedChildProperties.put(childDescriptor.getPriorityProperty(), "0");
            }
            if (childDescriptor.isContainer()) {
                assertThat(actualChild, instanceOf(ContainerChainElement.class));
            }
            assertThat(actualChild.getId(), equalTo(UUID_VALUE.toString()));
            assertThat(actualChild.getName(), equalTo(childDescriptor.getTitle()));
            assertThat(actualChild.getType(), equalTo(childDescriptor.getName()));
            assertThat(actualChild.getChain(), samePropertyValuesAs(testChain));
            assertPropertiesAreEqual(actualChild.getProperties(), expectedChildProperties);
            verify(elementRepository, times(2)).save(argThat(element -> element == actualChild));
        }
    }

    @DisplayName("Creating chain element with parent")
    @Test
    public void createElementWithParentTest() {
        ContainerChainElement parentElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        when(elementRepository.findByIdAndChainId(eq(parentElement.getId()), eq(TestElementUtils.CHAIN_ID))).thenReturn(parentElement);
        when(elementRepository.save(ArgumentMatchers.any(ChainElement.class))).thenAnswer(i -> i.getArguments()[0]);

        ElementDescriptor descriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_CASE_TYPE);
        Map<String, Object> expectedProperties = extractElementProperties(descriptor);
        if (descriptor.isOrdered()) {
            expectedProperties.put(descriptor.getPriorityProperty(), "0");
        }

        CreateElementRequest request = CreateElementRequest.builder()
                .type(TestElementUtils.TEST_CASE_TYPE)
                .parentElementId(parentElement.getId())
                .build();
        ChainDiff chainDiff = elementService.create(TestElementUtils.CHAIN_ID, request);

        assertThat(chainDiff.getCreatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), hasSize(1));
        assertThat(chainDiff.getUpdatedElements(), hasItem(parentElement));
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());

        ChainElement actual = chainDiff.getCreatedElements().get(0);

        assertThat(actual.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actual.getName(), equalTo(descriptor.getTitle()));
        assertThat(actual.getType(), equalTo(descriptor.getName()));
        assertThat(actual.getParent(), samePropertyValuesAs(parentElement));
        assertThat(actual.getChain(), samePropertyValuesAs(testChain));
        assertPropertiesAreEqual(actual.getProperties(), expectedProperties);
        verify(elementRepository, times(1)).save(eq(parentElement));
        verify(elementRepository, times(2)).save(eq(actual));
    }

    private static Stream<Arguments> createElementWithExceptionTestData() {
        return Stream.of(
                Arguments.of(
                        "Non-container parent",
                        TestElementUtils.TEST_CASE_TYPE,
                        ChainElement.builder().type(TestElementUtils.TEST_SWITCH_TYPE).build(),
                        ElementCreationException.class
                ),
                Arguments.of(
                        "Unknown element type",
                        "unknown",
                        ContainerChainElement.builder().type(TestElementUtils.TEST_SWITCH_TYPE).build(),
                        ElementValidationException.class
                ),
                Arguments.of(
                        "Empty parent type",
                        TestElementUtils.TEST_CASE_TYPE,
                        new ContainerChainElement(),
                        ElementValidationException.class
                ),
                Arguments.of(
                        "Incorrect parent type",
                        TestElementUtils.TEST_CASE_TYPE,
                        ContainerChainElement.builder().type(TestElementUtils.TEST_SENDER_TYPE).build(),
                        ElementValidationException.class
                ),
                Arguments.of(
                        "Child with disabled input",
                        TestElementUtils.TEST_TRIGGER_TYPE,
                        ContainerChainElement.builder().type(TestElementUtils.TEST_CASE_TYPE).build(),
                        ElementValidationException.class
                ),
                Arguments.of(
                        "Exceeded the number of elements of the same type",
                        TestElementUtils.TEST_DEFAULT_TYPE,
                        ContainerChainElement.builder()
                                .type(TestElementUtils.TEST_SWITCH_TYPE)
                                .elements(Collections.singletonList(ChainElement.builder().type(TestElementUtils.TEST_DEFAULT_TYPE).build()))
                                .build(),
                        ElementValidationException.class
                )
        );
    }

    @DisplayName("Error creating chain element")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("createElementWithExceptionTestData")
    public void createElementWithExceptionTest(
            String scenario,
            String elementType,
            ChainElement parentElement,
            Class<Throwable> expectedException
    ) {
        if (parentElement != null) {
            parentElement.setChain(testChain);

            when(elementRepository.findByIdAndChainId(eq(parentElement.getId()), eq(TestElementUtils.CHAIN_ID))).thenReturn(parentElement);
        }
        when(elementRepository.save(ArgumentMatchers.any(ChainElement.class))).thenAnswer(i -> i.getArguments()[0]);

        CreateElementRequest request = CreateElementRequest.builder()
                .type(elementType)
                .parentElementId(parentElement != null ? parentElement.getId() : null)
                .build();
        assertThrows(expectedException, () -> elementService.create(TestElementUtils.CHAIN_ID, request));
    }

    @DisplayName("Deleting by id and updating unsaved")
    @Test
    public void deleteByIdTest() {
        ChainElement element = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        when(elementRepository.findById(eq(element.getId()))).thenReturn(Optional.of(element));
        when(elementRepository.getReferenceById(eq(element.getId()))).thenReturn(element);
        doNothing().when(elementRepository).deleteAll(any());

        ChainDiff chainDiff = elementService.deleteByIdAndUpdateUnsaved(element.getId());

        assertThat(chainDiff.getRemovedElements(), hasSize(1));
        assertThat(chainDiff.getRemovedElements(), hasItem(element));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(elementRepository, times(1)).deleteAll(eq(chainDiff.getRemovedElements()));
    }

    @DisplayName("Deleting container with children by id and updating unsaved")
    @Test
    public void deleteContainerWithChildrenByIdTest() {
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ContainerChainElement switchElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        caseElement.setElements(Collections.singletonList(senderElement));
        switchElement.setElements(Collections.singletonList(caseElement));
        when(elementRepository.findById(eq(switchElement.getId()))).thenReturn(Optional.of(switchElement));
        when(elementRepository.getReferenceById(eq(switchElement.getId()))).thenReturn(switchElement);
        doNothing().when(elementRepository).deleteAll(any());

        ChainDiff chainDiff = elementService.deleteByIdAndUpdateUnsaved(switchElement.getId());

        assertThat(chainDiff.getRemovedElements(), hasSize(3));
        assertThat(chainDiff.getRemovedElements(), hasItems(switchElement, caseElement, senderElement));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(elementRepository, times(1)).deleteAll(eq(chainDiff.getRemovedElements()));
    }

    @DisplayName("Deleting element with parent by id")
    @Test
    public void deleteElementWithParentByIdTest() {
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ChainElement senderElement1 = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        ChainElement senderElement2 = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_2_ID);
        ChainElement switchElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        Dependency sender1ToSwitchDependency = Dependency.of(senderElement1, switchElement);
        sender1ToSwitchDependency.setId("bd042300-9402-4b6e-8110-62f2e4e09bc1");
        Dependency switchToSender2Dependency = Dependency.of(switchElement, senderElement2);
        switchToSender2Dependency.setId("0aebc1ff-25d0-4851-8d45-db257740eafa");
        senderElement1.setOutputDependencies(new ArrayList<>(Collections.singletonList(sender1ToSwitchDependency)));
        switchElement.setInputDependencies(new ArrayList<>(Collections.singletonList(sender1ToSwitchDependency)));
        switchElement.setOutputDependencies(new ArrayList<>(Collections.singletonList(switchToSender2Dependency)));
        senderElement2.setInputDependencies(new ArrayList<>(Collections.singletonList(switchToSender2Dependency)));
        caseElement.addChildrenElements(Arrays.asList(senderElement1, senderElement2, switchElement));
        when(elementRepository.findById(eq(switchElement.getId()))).thenReturn(Optional.of(switchElement));
        when(elementRepository.getReferenceById(eq(switchElement.getId()))).thenReturn(switchElement);
        when(jpaAuditingHandler.markModified(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(caseElement))).thenAnswer(i -> i.getArguments()[0]);
        doNothing().when(elementRepository).deleteAll(any());

        ChainDiff chainDiff = elementService.deleteByIdAndUpdateUnsaved(switchElement.getId());

        assertThat(chainDiff.getRemovedElements(), hasSize(1));
        assertThat(chainDiff.getRemovedElements(), hasItems(switchElement));
        assertThat(chainDiff.getRemovedDependencies(), hasSize(2));
        assertThat(chainDiff.getRemovedDependencies(), hasItems(sender1ToSwitchDependency, switchToSender2Dependency));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        verify(elementRepository, times(1)).deleteAll(eq(chainDiff.getRemovedElements()));
        verify(elementRepository, times(1)).save(eq(caseElement));
    }

    @DisplayName("Deleting ordered element")
    @Test
    public void deleteOrderedElementByIdTest() {
        ContainerChainElement switchElement = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        ContainerChainElement firstCaseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ContainerChainElement secondCaseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_2_ID);
        ElementDescriptor descriptor = libraryService.getElementDescriptor(TestElementUtils.TEST_CASE_TYPE);
        firstCaseElement.getProperties().put(descriptor.getPriorityProperty(), 0);
        secondCaseElement.getProperties().put(descriptor.getPriorityProperty(), 1);
        switchElement.addChildrenElements(Arrays.asList(firstCaseElement, secondCaseElement));
        when(elementRepository.findById(eq(firstCaseElement.getId()))).thenReturn(Optional.of(firstCaseElement));
        when(elementRepository.getReferenceById(eq(firstCaseElement.getId()))).thenReturn(firstCaseElement);
        when(jpaAuditingHandler.markModified(eq(secondCaseElement))).thenAnswer(i -> i.getArguments()[0]);
        when(jpaAuditingHandler.markModified(eq(switchElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(switchElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.saveAll(argThat(elements -> IterableUtils.contains(elements, secondCaseElement))))
                .thenAnswer(i -> i.getArguments()[0]);
        doNothing().when(elementRepository).deleteAll(any());

        ChainDiff chainDiff = elementService.deleteByIdAndUpdateUnsaved(firstCaseElement.getId());

        assertThat(chainDiff.getRemovedElements(), hasSize(1));
        assertThat(chainDiff.getRemovedElements(), hasItem(firstCaseElement));
        assertThat(chainDiff.getUpdatedElements(), hasSize(2));
        assertThat(chainDiff.getUpdatedElements(), hasItems(secondCaseElement, switchElement));
        assertThat(secondCaseElement.getProperty(descriptor.getPriorityProperty()), equalTo(0));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
        verify(jpaAuditingHandler, times(1)).markModified(eq(secondCaseElement));
        verify(elementRepository, times(1))
                .saveAll(argThat(elements -> IterableUtils.contains(elements, secondCaseElement)));
        verify(elementRepository, times(1)).deleteAll(eq(chainDiff.getRemovedElements()));
    }

    private static Stream<Arguments> deleteLastAllowedChildByIdTestData() {
        return Stream.of(
                Arguments.of("Two or many", TestElementUtils.CASE_2_ID),
                Arguments.of("One or many", TestElementUtils.DEFAULT_ID),
                Arguments.of("One", TestElementUtils.SENDER_1_ID)
        );
    }

    @DisplayName("Deleting last allowed child by id")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("deleteLastAllowedChildByIdTestData")
    public void deleteLastAllowedChildByIdTest(String scenario, String id) {
        ContainerChainElement containerElement = createContainerElement(TestElementUtils.TEST_CONTAINER_TYPE, TestElementUtils.CONTAINER_ID);
        ContainerChainElement case1Element = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        ContainerChainElement case2Element = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_2_ID);
        case1Element.getProperties().put(libraryService.getElementDescriptor(TestElementUtils.TEST_CASE_TYPE).getPriorityProperty(), 0);
        case2Element.getProperties().put(libraryService.getElementDescriptor(TestElementUtils.TEST_CASE_TYPE).getPriorityProperty(), 1);
        Map<String, ChainElement> children = ImmutableMap.<String, ChainElement>builder()
                .put(TestElementUtils.CASE_1_ID, case1Element)
                .put(TestElementUtils.CASE_2_ID, case2Element)
                .put(TestElementUtils.DEFAULT_ID, createContainerElement(TestElementUtils.TEST_DEFAULT_TYPE, TestElementUtils.DEFAULT_ID))
                .put(TestElementUtils.SENDER_1_ID, createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID))
                .build();
        containerElement.addChildrenElements(children.values());
        when(elementRepository.findById(eq(id))).thenReturn(Optional.ofNullable(children.get(id)));
        when(elementRepository.getReferenceById(eq(id))).thenReturn(children.get(id));
        when(jpaAuditingHandler.markModified(eq(containerElement))).thenAnswer(i -> i.getArguments()[0]);
        when(elementRepository.save(eq(containerElement))).thenAnswer(i -> i.getArguments()[0]);

        assertThrows(ElementValidationException.class, () -> elementService.deleteByIdAndUpdateUnsaved(id));
    }

    @DisplayName("Changing parent")
    @Test
    public void changeParentTest() {
        ContainerChainElement containerElement = createContainerElement(TestElementUtils.TEST_CONTAINER_TYPE, TestElementUtils.CONTAINER_ID);
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        containerElement.addChildElement(caseElement);

        ContainerChainElement newParent = createContainerElement(TestElementUtils.TEST_SWITCH_TYPE, TestElementUtils.SWITCH_1_ID);
        when(elementRepository.findById(eq(TestElementUtils.SWITCH_1_ID))).thenReturn(Optional.of(newParent));
        when(jpaAuditingHandler.markModified(eq(containerElement))).thenAnswer(i -> i.getArguments()[0]);
        when(jpaAuditingHandler.markModified(eq(newParent))).thenAnswer(i -> i.getArguments()[0]);

        ChainElement actual = elementService.changeParent(caseElement, TestElementUtils.SWITCH_1_ID);

        assertThat(actual.getParent(), equalTo(newParent));
        assertThat(containerElement.getElements(), empty());
        assertThat(newParent.getElements(), hasSize(1));
        assertThat(newParent.getElements(), hasItem(actual));
        verify(jpaAuditingHandler, times(1)).markModified(eq(containerElement));
        verify(jpaAuditingHandler, times(1)).markModified(eq(newParent));
    }

    @DisplayName("Changing parent to null")
    @Test
    public void changeParentToNullTest() {
        ContainerChainElement switchElement = createContainerElement(TestElementUtils.TEST_CONTAINER_TYPE, TestElementUtils.CONTAINER_ID);
        ContainerChainElement caseElement = createContainerElement(TestElementUtils.TEST_CASE_TYPE, TestElementUtils.CASE_1_ID);
        switchElement.addChildElement(caseElement);
        when(jpaAuditingHandler.markModified(eq(switchElement))).thenAnswer(i -> i.getArguments()[0]);

        ChainElement actual = elementService.changeParent(caseElement, null);

        assertThat(actual.getParent(), is(nullValue()));
        assertThat(switchElement.getElements(), empty());
        verify(jpaAuditingHandler, times(1)).markModified(eq(switchElement));
    }

    @DisplayName("Grouping elements")
    @Test
    public void groupElementsTest() {
        ChainElement triggerElement = createChainElement(TestElementUtils.TEST_TRIGGER_TYPE, TestElementUtils.TRIGGER_ID);
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        Dependency triggerToSenderDependency = Dependency.of(triggerElement, senderElement);
        triggerElement.addOutputDependency(triggerToSenderDependency);
        senderElement.addInputDependency(triggerToSenderDependency);
        ContainerChainElement containerElement = createContainerElement(TestElementUtils.TEST_CONTAINER_TYPE, TestElementUtils.CONTAINER_ID);
        when(chainService.findById(eq(TestElementUtils.CHAIN_ID))).thenReturn(testChain);
        when(elementRepository.findAllById(eq(List.of(TestElementUtils.TRIGGER_ID, TestElementUtils.SENDER_1_ID, TestElementUtils.CONTAINER_ID))))
                .thenReturn(List.of(triggerElement, senderElement, containerElement));
        when(elementRepository.saveEntity(argThat(group -> UUID_VALUE.toString().equals(group.getId()) && CONTAINER_TYPE_NAME.equals(group.getType()))))
                .thenAnswer(i -> i.getArguments()[0]);

        ChainElement actual = elementService.group(TestElementUtils.CHAIN_ID, List.of(TestElementUtils.TRIGGER_ID, TestElementUtils.SENDER_1_ID, TestElementUtils.CONTAINER_ID));

        assertThat(actual, instanceOf(ContainerChainElement.class));
        assertThat(actual.getParent(), is(nullValue()));

        ContainerChainElement actualContainer = (ContainerChainElement) actual;

        assertThat(actualContainer.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actualContainer.getName(), equalTo(CONTAINER_DEFAULT_NAME));
        assertThat(actualContainer.getType(), equalTo(CONTAINER_TYPE_NAME));
        assertThat(actualContainer.getChain(), equalTo(testChain));
        assertThat(actualContainer.getElements(), hasSize(3));
        assertThat(actualContainer.getElements(), hasItems(triggerElement, senderElement, containerElement));
        verify(elementRepository, times(1)).saveEntity(eq(actualContainer));
    }

    @DisplayName("Grouping elements with parent")
    @Test
    public void groupElementsWithParentTest() {
        ContainerChainElement containerElement = createContainerElement(TestElementUtils.TEST_CONTAINER_TYPE, TestElementUtils.CONTAINER_ID);
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        containerElement.addChildElement(senderElement);
        ChainElement triggerElement = createChainElement(TestElementUtils.TEST_TRIGGER_TYPE, TestElementUtils.TRIGGER_ID);
        when(chainService.findById(eq(TestElementUtils.CHAIN_ID))).thenReturn(testChain);
        when(elementRepository.findAllById(eq(List.of(TestElementUtils.SENDER_1_ID, TestElementUtils.TRIGGER_ID)))).thenReturn(List.of(senderElement, triggerElement));

        assertThrows(ElementValidationException.class, () -> elementService.group(TestElementUtils.CHAIN_ID, List.of(TestElementUtils.SENDER_1_ID, TestElementUtils.TRIGGER_ID)));
    }

    @DisplayName("Ungrouping elements")
    @Test
    public void ungroupElementsTest() {
        ChainElement triggerElement = createChainElement(TestElementUtils.TEST_TRIGGER_TYPE, TestElementUtils.TRIGGER_ID);
        ChainElement senderElement = createChainElement(TestElementUtils.TEST_SENDER_TYPE, TestElementUtils.SENDER_1_ID);
        ContainerChainElement groupElement = ContainerChainElement.builder()
                .type(CONTAINER_TYPE_NAME)
                .name(CONTAINER_DEFAULT_NAME)
                .chain(testChain)
                .build();
        groupElement.addChildrenElements(List.of(triggerElement, senderElement));
        when(elementRepository.findById(eq(groupElement.getId()))).thenReturn(Optional.of(groupElement));
        when(elementRepository.saveAll(eq(List.of(triggerElement, senderElement)))).thenAnswer(i -> i.getArguments()[0]);
        doNothing().when(elementRepository).delete(eq(groupElement));

        List<ChainElement> actual = elementService.ungroup(groupElement.getId());

        assertThat(actual, hasSize(2));
        assertThat(actual, hasItems(triggerElement, senderElement));
        assertThat(triggerElement.getParent(), is(nullValue()));
        assertThat(senderElement.getParent(), is(nullValue()));
        verify(elementRepository, times(1)).saveAll(eq(List.of(triggerElement, senderElement)));
        verify(elementRepository, times(1)).delete(eq(groupElement));
    }

    @DisplayName("Ungrouping elements of non-group container")
    @Test
    public void ungroupElementsOfNonGroupContainerTest() {
        ContainerChainElement containerElement = createContainerElement(TestElementUtils.TEST_CONTAINER_TYPE, TestElementUtils.CONTAINER_ID);
        when(elementRepository.findById(eq(TestElementUtils.CONTAINER_ID))).thenReturn(Optional.of(containerElement));

        assertThrows(ElementValidationException.class, () -> elementService.ungroup(TestElementUtils.CONTAINER_ID));
    }

    private void assertPropertiesAreEqual(Map<String, Object> actual, Map<String, Object> expected) {
        assertThat(actual, aMapWithSize(expected.size()));
        for (Map.Entry<String, Object> actualProperty : actual.entrySet()) {
            String actualKey = actualProperty.getKey();
            String actualValue = String.valueOf(actualProperty.getValue());

            assertThat(actualKey, is(in(expected.keySet())));

            String expectedProperty = String.valueOf(expected.get(actualKey));

            assertThat("Property " + actualKey, actualValue, equalTo(expectedProperty));
        }
    }

    private Map<String, Object> extractElementProperties(ElementDescriptor descriptor) {
        return descriptor.getProperties().getAll().stream()
                .filter(it -> it.getDefaultValue() != null)
                .map(it -> Pair.of(it.getName(), it.getDefaultValue()))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
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
