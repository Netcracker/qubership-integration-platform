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

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.MapperInterpretatorHelper;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.testutils.configuration.TestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.*;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.collection.IsIn.in;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ContextConfiguration(classes = {
        TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryElementsService.class,
        LibraryResourceLoader.class,
        OrderedElementService.class
})
@ExtendWith(SpringExtension.class)
public class OrderedElementServiceTest {

    private static final String PARENT_ELEMENT_TYPE = "test-switch";
    private static final String ORDERED_ELEMENT_TYPE = "test-case";
    private static final String FIRST_ELEMENT_ID = "1";
    private static final String SECOND_ELEMENT_ID = "2";
    private static final String THIRD_ELEMENT_ID = "3";
    private static final String FOURTH_ELEMENT_ID = "4";

    @Autowired
    private LibraryElementsService libraryService;
    @Autowired
    private OrderedElementService orderedElementService;

    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;

    private ContainerChainElement parentElement;
    private Map<String, ChainElement> elements;


    @BeforeEach
    public void initializeBeforeEach() {
        this.parentElement = new ContainerChainElement();
        this.parentElement.setType(PARENT_ELEMENT_TYPE);

        ElementDescriptor descriptor = libraryService.getElementDescriptor(ORDERED_ELEMENT_TYPE);
        this.elements = ImmutableMap.<String, ChainElement>builder()
                .put(FIRST_ELEMENT_ID, createElementWithPriority(FIRST_ELEMENT_ID, descriptor.getPriorityProperty(), 0))
                .put(SECOND_ELEMENT_ID, createElementWithPriority(SECOND_ELEMENT_ID, descriptor.getPriorityProperty(), 1))
                .put(THIRD_ELEMENT_ID, createElementWithPriority(THIRD_ELEMENT_ID, descriptor.getPriorityProperty(), 2))
                .put(FOURTH_ELEMENT_ID, createElementWithPriority(FOURTH_ELEMENT_ID, descriptor.getPriorityProperty(), 99))
                .build();
        this.parentElement.addChildrenElements(Arrays.asList(
                elements.get(THIRD_ELEMENT_ID),
                elements.get(FIRST_ELEMENT_ID),
                elements.get(FOURTH_ELEMENT_ID),
                elements.get(SECOND_ELEMENT_ID)
        ));
    }

    private ChainElement createElementWithPriority(String id, String priorityKey, Integer priority) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(priorityKey, priority);

        return ContainerChainElement.builder()
                .id(id)
                .type(ORDERED_ELEMENT_TYPE)
                .properties(properties)
                .build();
    }

    private static Stream<Arguments> isOrderedTestData() {
        return Stream.of(
                Arguments.of(
                        "Ordered element",
                        ORDERED_ELEMENT_TYPE,
                        ContainerChainElement.builder().type(PARENT_ELEMENT_TYPE).build(),
                        true
                ),
                Arguments.of(
                        "Ordered element without parent",
                        ORDERED_ELEMENT_TYPE,
                        null,
                        false
                ),
                Arguments.of(
                        "Non-ordered element",
                        PARENT_ELEMENT_TYPE,
                        null,
                        false),
                Arguments.of(
                        "Unknown element with parent",
                        "unknown",
                        ContainerChainElement.builder().type(PARENT_ELEMENT_TYPE).build(),
                        false
                )
        );
    }

    @DisplayName("Checking if an element is ordered")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("isOrderedTestData")
    public void isOrderedTest(String scenario, String elementType, ContainerChainElement parentElement, boolean expected) {
        ChainElement element = ChainElement.builder()
                .type(elementType)
                .parent(parentElement)
                .build();

        boolean actual = orderedElementService.isOrdered(element);

        assertThat(actual, equalTo(expected));
    }

    @DisplayName("Extracting element priority number")
    @Test
    public void extractPriorityNumberTest() {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(ORDERED_ELEMENT_TYPE);
        Map<String, Object> properties = new HashMap<>();

        Optional<Integer> actual = orderedElementService.extractPriorityNumber(ORDERED_ELEMENT_TYPE, properties);

        assertTrue(actual.isEmpty());

        Integer expected = 123;

        properties.put(descriptor.getPriorityProperty(), expected);

        actual = orderedElementService.extractPriorityNumber(ORDERED_ELEMENT_TYPE, properties);

        assertFalse(actual.isEmpty());
        assertThat(actual.get(), equalTo(expected));
    }

    @DisplayName("Calculating element priority")
    @Test
    public void calculatePriorityTest() {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(ORDERED_ELEMENT_TYPE);
        ChainElement element = new ChainElement();
        element.setType(ORDERED_ELEMENT_TYPE);

        orderedElementService.calculatePriority(parentElement, element);
        Object actual = element.getProperty(descriptor.getPriorityProperty());

        assertThat(actual, equalTo(3));
    }

    private static Stream<Arguments> changePriorityTestData() {
        return Stream.of(
                Arguments.of(
                        "Move second up",
                        SECOND_ELEMENT_ID,
                        0,
                        ImmutableMap.<String, String>builder()
                                .put(FIRST_ELEMENT_ID, "1")
                                .build()
                ),
                Arguments.of(
                        "Move first down",
                        FIRST_ELEMENT_ID,
                        1,
                        ImmutableMap.<String, String>builder()
                                .put(SECOND_ELEMENT_ID, "0")
                                .build()
                ),
                Arguments.of(
                        "Move first down to priority 2",
                        FIRST_ELEMENT_ID,
                        2,
                        ImmutableMap.<String, String>builder()
                                .put(SECOND_ELEMENT_ID, "0")
                                .put(THIRD_ELEMENT_ID, "1")
                                .build()
                ),
                Arguments.of(
                        "Move fourth up to priority 0",
                        FOURTH_ELEMENT_ID,
                        0,
                        ImmutableMap.<String, String>builder()
                                .put(FIRST_ELEMENT_ID, "1")
                                .put(SECOND_ELEMENT_ID, "2")
                                .put(THIRD_ELEMENT_ID, "3")
                                .build()
                ),
                Arguments.of(
                        "Move first down to priority 100",
                        FIRST_ELEMENT_ID,
                        100,
                        ImmutableMap.<String, String>builder()
                                .put(SECOND_ELEMENT_ID, "0")
                                .put(THIRD_ELEMENT_ID, "1")
                                .build()
                ),
                Arguments.of(
                        "Move fourth up to priority 50",
                        FOURTH_ELEMENT_ID,
                        50,
                        Collections.emptyMap()
                ),
                Arguments.of(
                        "Move fourth down to priority 100",
                        FOURTH_ELEMENT_ID,
                        100,
                        Collections.emptyMap()
                )
        );
    }

    @DisplayName("Changing element priority")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("changePriorityTestData")
    public void changePriorityTest(String scenario, String elementId, Integer newPriority, Map<String, String> expected) {
        ChainElement testElement = elements.get(elementId);
        ElementDescriptor descriptor = libraryService.getElementDescriptor(testElement);
        ChainDiff chainDiff = orderedElementService.changePriority(parentElement, testElement, newPriority);

        assertThat(chainDiff.getUpdatedElements().size(), equalTo(expected.size()));

        for (ChainElement actual : chainDiff.getUpdatedElements()) {
            assertThat(actual.getId(), is(in(expected.keySet())));

            String actualPriority = String.valueOf(actual.getProperty(descriptor.getPriorityProperty()));

            assertThat(actualPriority, equalTo(expected.get(actual.getId())));
        }

        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
    }

    private static Stream<Arguments> removeOrderedElementTestData() {
        return Stream.of(
                Arguments.of(
                        "Remove first",
                        FIRST_ELEMENT_ID,
                        ImmutableMap.<String, String>builder()
                                .put(SECOND_ELEMENT_ID, "0")
                                .put(THIRD_ELEMENT_ID, "1")
                                .build()
                ),
                Arguments.of(
                        "Remove second",
                        SECOND_ELEMENT_ID,
                        ImmutableMap.<String, String>builder()
                                .put(THIRD_ELEMENT_ID, "1")
                                .build()
                ),
                Arguments.of(
                        "Remove third",
                        THIRD_ELEMENT_ID,
                        Collections.emptyMap()
                ),
                Arguments.of(
                        "Remove fourth",
                        FOURTH_ELEMENT_ID,
                        Collections.emptyMap()
                )
        );
    }

    @DisplayName("Removing ordered element")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("removeOrderedElementTestData")
    public void removeOrderedElementTest(String scenario, String elementId, Map<String, String> expected) {
        ChainElement testElement = elements.get(elementId);
        ElementDescriptor descriptor = libraryService.getElementDescriptor(testElement);
        ChainDiff chainDiff = orderedElementService.removeOrderedElement(parentElement, testElement);

        assertThat(chainDiff.getUpdatedElements().size(), equalTo(expected.size()));

        for (ChainElement actual : chainDiff.getUpdatedElements()) {
            assertThat(actual.getId(), is(in(expected.keySet())));

            String actualPriority = String.valueOf(actual.getProperty(descriptor.getPriorityProperty()));

            assertThat(actualPriority, equalTo(expected.get(actual.getId())));
        }

        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), empty());
    }
}
