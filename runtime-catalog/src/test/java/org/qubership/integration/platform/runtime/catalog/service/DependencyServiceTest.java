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

import jakarta.persistence.EntityExistsException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.MapperInterpretatorHelper;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DependencyValidationException;
import org.qubership.integration.platform.runtime.catalog.model.ChainDiff;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ChainFinderService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.qubership.integration.platform.runtime.catalog.testutils.configuration.TestConfig;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainElementsMapper;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainMapper;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.util.OldContainerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ContextConfiguration(classes = {
        TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryResourceLoader.class,
        LibraryElementsService.class,
        OrderedElementService.class,
        ElementUtils.class,
        ElementService.class,
        OldContainerUtils.class,
        DependencyService.class,
        ChainElementsMapper.class,
        ChainMapper.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class DependencyServiceTest {

    private static final UUID UUID_VALUE = UUID.fromString("90729468-b451-42d2-ba8f-344027fa2798");
    private static final String HTTP_TRIGGER_ID = "00edd74c-95a5-454e-9e67-01e20772e679";
    private static final String HTTP_TRIGGER_SCRIPT_ID = "63b3a8e8-ae38-4092-ba91-db5c22c8590b";
    private static final String DEPRECATED_TRY_ID = "934113d8-5dad-4568-8e68-8e856f4b63dd";
    private static final String DEPRECATED_TRY_MAPPER_ID = "3c8f5cbc-89d9-4a47-92da-83254731bfec";
    private static final String LOOP_ID = "cbf89841-b96f-4351-bfd5-fa8f8a18ec2b";
    private static final String IF_SCRIPT_ID = "d2a06b2a-33d3-46d7-8df9-250d9d3ab19b";
    private static final String LOOP_INSIDE_MAPPER_ID = "b5eae22b-ace2-422b-9682-4d48850b40ea";
    private static final String LOOP_SCRIPT_ID = "1d2ea84f-76f4-47f2-a935-10fb1ca491fb";
    private static final String DEPRECATED_TRY_CATCH_FINALLY_ID = "16cbcda3-100f-4860-b6d5-049ec1494f35";
    private static final String IF_ID = "2c70fd43-147f-442e-9525-38d7141f9ccb";
    private static final String SCRIPT_1_ID = "99bd6756-ad98-4cf6-917e-382c49aa902e";
    private static final String SCRIPT_2_ID = "80d0dfc5-5c12-4b5e-acfe-4ba39350e4ee";
    private static final String SCRIPT_1_MAPPER_1_ID = "af5cbe4b-188f-4911-95c4-5a57e78f155d";
    private static final String SCRIPT_1_TRY_CATCH_FINALLY_ID = "c1d44337-f550-4ff1-81c1-55650ec86424";
    private static final String GROUP_CONTAINER_ID = "e2d697ea-061f-4809-a3b3-d4a798c37763";
    private static final String GROUP_CONTAINER_SCRIPT_ID = "a530d1fe-4bc6-44af-8720-12034ab7892d";
    private static final String SCRIPT_1_TRY_INSIDE_MAPPER_ID = "4daba6c9-7e54-455f-9bb5-cc81b3811cff";
    private static final String SCRIPT_1_TRY_INSIDE_SCRIPT_ID = "5eac05f3-2073-40e3-973a-62761ae934d5";
    private static final String ASYNC_SPLIT_ID = "f977e104-6ecc-4792-9adc-994024c0a684";
    private static final String ASYNC_SPLIT_ELEMENT_SCRIPT_ID = "93eceee1-8497-4e7d-a657-900f685eef09";
    private static final String SYNC_SPLIT_ELEMENT_MAPPER_ID = "a17da5ad-391f-426e-b9c4-a3dc89a5dba1";
    private static final String SYNC_SPLIT_ELEMENT_SCRIPT_ID = "23ba5a24-9b92-437b-9891-16aa3bbe2232";
    private static final String SCRIPT_2_MAPPER_SCRIPT_ID = "99eecadf-cc71-43f2-8b19-34f9a780978b";

    private static MockedStatic<UUID> mockedUUID;

    @MockBean
    ElementRepository elementRepository;
    @MockBean
    ChainService chainService;
    @MockBean
    SwimlaneService swimlaneService;
    @MockBean
    ActionsLogService actionsLogService;
    @MockBean
    AuditingHandler jpaAuditingHandler;
    @MockBean
    DependencyRepository dependencyRepository;
    @MockBean
    EnvironmentService environmentService;
    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;
    @MockBean
    SystemEnvironmentsGenerator systemEnvironmentsGenerator;
    @MockBean
    ChainFinderService chainFinderService;


    private final ChainMapper chainMapper;
    private final DependencyService dependencyService;
    private Map<String, ChainElement> elements;

    @Autowired
    public DependencyServiceTest(ChainMapper chainMapper, DependencyService dependencyService) {
        this.chainMapper = chainMapper;
        this.dependencyService = dependencyService;
    }

    @BeforeAll
    public static void initializeBeforeAll() {
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        mockedUUID.when(UUID::randomUUID).thenReturn(UUID_VALUE);
    }

    @AfterAll
    public static void finalizeAfterAll() {
        mockedUUID.close();
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        when(jpaAuditingHandler.markModified(any(ChainElement.class))).thenAnswer(i -> i.getArguments()[0]);
        when(dependencyRepository.save(any(Dependency.class))).thenAnswer(i -> i.getArguments()[0]);
        ChainImportDTO chainDTO = TestUtils.YAML_MAPPER.readValue(
                TestUtils.getResourceFileContent("/testData/input/service/dependency/chain.yml"),
                ChainImportDTO.class
        );
        this.elements = chainMapper.toEntity(chainDTO).getRootElements().stream()
                .flatMap(element -> {
                    Stream<ChainElement> elementStream = Stream.of(element);
                    if (element instanceof ContainerChainElement container) {
                        elementStream = Stream.concat(elementStream, container.extractAllChildElements().values().stream());
                    }
                    return elementStream;
                })
                .collect(Collectors.toMap(ChainElement::getId, Function.identity()));
    }

    @DisplayName("Creating dependency between root elements")
    @Test
    public void createTest() {
        ChainElement httpTrigger = getElementById(HTTP_TRIGGER_ID);
        ChainElement httpTriggerScript = getElementById(HTTP_TRIGGER_SCRIPT_ID);
        when(elementRepository.findById(eq(HTTP_TRIGGER_ID))).thenReturn(Optional.of(httpTrigger));
        when(elementRepository.findById(eq(HTTP_TRIGGER_SCRIPT_ID))).thenReturn(Optional.of(httpTriggerScript));
        when(dependencyRepository.findByFromAndTo(eq(HTTP_TRIGGER_ID), eq(HTTP_TRIGGER_SCRIPT_ID))).thenReturn(Optional.empty());

        ChainDiff chainDiff = dependencyService.create(HTTP_TRIGGER_ID, HTTP_TRIGGER_SCRIPT_ID);

        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getRemovedDependencies(), empty());
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());

        Dependency dependency = chainDiff.getCreatedDependencies().get(0);

        assertThat(dependency.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(dependency.getElementFrom(), equalTo(httpTrigger));
        assertThat(dependency.getElementTo(), equalTo(httpTriggerScript));
        assertThat(httpTrigger.getInputDependencies(), empty());
        assertThat(httpTrigger.getOutputDependencies(), hasSize(1));
        assertThat(httpTrigger.getOutputDependencies(), Matchers.contains(dependency));
        assertThat(httpTriggerScript.getInputDependencies(), hasSize(1));
        assertThat(httpTriggerScript.getOutputDependencies(), empty());
        assertThat(httpTriggerScript.getInputDependencies(), Matchers.contains(dependency));
        assertThat(httpTrigger.getParent(), is(nullValue()));
        assertThat(httpTriggerScript.getParent(), is(nullValue()));
        verify(elementRepository, never()).save(any());
        verify(dependencyRepository, times(1)).save(eq(dependency));
    }

    @DisplayName("Creating dependency between deprecated container child and root element")
    @Test
    public void createFromDeprecatedChildTest() {
        ChainElement tryElement = getElementById(DEPRECATED_TRY_ID);
        ChainElement tryMapperElement = getElementById(DEPRECATED_TRY_MAPPER_ID);
        ChainElement tryCatchFinally = getElementById(DEPRECATED_TRY_CATCH_FINALLY_ID);
        when(elementRepository.findById(eq(DEPRECATED_TRY_ID))).thenReturn(Optional.of(tryElement));
        when(elementRepository.findById(eq(DEPRECATED_TRY_MAPPER_ID))).thenReturn(Optional.of(tryMapperElement));
        when(dependencyRepository.findByFromAndTo(eq(DEPRECATED_TRY_ID), eq(DEPRECATED_TRY_MAPPER_ID))).thenReturn(Optional.empty());

        ChainDiff chainDiff = dependencyService.create(DEPRECATED_TRY_ID, DEPRECATED_TRY_MAPPER_ID);

        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getRemovedDependencies(), empty());
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());

        Dependency dependency = chainDiff.getCreatedDependencies().get(0);

        assertThat(dependency.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(dependency.getElementFrom(), equalTo(tryElement));
        assertThat(dependency.getElementTo(), equalTo(tryMapperElement));
        assertThat(tryElement.getInputDependencies(), empty());
        assertThat(tryElement.getOutputDependencies(), hasSize(1));
        assertThat(tryElement.getOutputDependencies(), Matchers.contains(dependency));
        assertThat(tryMapperElement.getInputDependencies(), hasSize(1));
        assertThat(tryMapperElement.getOutputDependencies(), empty());
        assertThat(tryMapperElement.getInputDependencies(), Matchers.contains(dependency));
        assertThat(tryElement.getParent(), equalTo(tryCatchFinally));
        assertThat(tryMapperElement.getParent(), is(nullValue()));
        verify(elementRepository, never()).save(any());
        verify(dependencyRepository, times(1)).save(eq(dependency));
    }

    private static Stream<Arguments> createFromContainerChildToRootElementTestData() {
        return Stream.of(
                Arguments.of(
                        "From Loop container to Script",
                        IF_ID,
                        LOOP_ID,
                        IF_SCRIPT_ID
                ),
                Arguments.of(
                        "From Loop Mapper to Script",
                        LOOP_ID,
                        LOOP_INSIDE_MAPPER_ID,
                        LOOP_SCRIPT_ID
                )
        );
    }

    @DisplayName("Creating dependency between container child and root element")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("createFromContainerChildToRootElementTestData")
    public void createFromContainerChildToRootElementTest(String scenario, String parentId, String fromId, String toId) {
        ChainElement elementFrom = getElementById(fromId);
        ChainElement elementTo = getElementById(toId);
        ChainElement parentElement = getElementById(parentId);
        when(elementRepository.findById(eq(fromId))).thenReturn(Optional.of(elementFrom));
        when(elementRepository.findById(eq(toId))).thenReturn(Optional.of(elementTo));
        when(dependencyRepository.findByFromAndTo(eq(fromId), eq(toId))).thenReturn(Optional.empty());
        when(elementRepository.save(eq(elementTo))).thenReturn(elementTo);

        ChainDiff chainDiff = dependencyService.create(fromId, toId);

        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getRemovedDependencies(), empty());
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), hasSize(1));
        assertThat(chainDiff.getRemovedElements(), empty());

        Dependency dependency = chainDiff.getCreatedDependencies().get(0);
        ChainElement element = chainDiff.getUpdatedElements().get(0);

        assertThat(dependency.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(dependency.getElementFrom(), equalTo(elementFrom));
        assertThat(dependency.getElementTo(), equalTo(elementTo));
        assertThat(elementFrom.getInputDependencies(), empty());
        assertThat(elementFrom.getOutputDependencies(), hasSize(1));
        assertThat(elementFrom.getOutputDependencies(), Matchers.contains(dependency));
        assertThat(elementTo.getInputDependencies(), hasSize(1));
        assertThat(elementTo.getOutputDependencies(), empty());
        assertThat(elementTo.getInputDependencies(), Matchers.contains(dependency));
        assertThat(chainDiff.getUpdatedElements(), Matchers.contains(element));
        assertThat(elementFrom.getParent(), equalTo(parentElement));
        assertThat(elementTo.getParent(), equalTo(parentElement));
        verify(elementRepository, times(1)).save(any());
        verify(elementRepository, times(1)).save(eq(elementTo));
        verify(dependencyRepository, times(1)).save(eq(dependency));
    }

    @DisplayName("Creating dependency between container child and elements chain")
    @Test
    public void createFromContainerChildToElementsChainTest() {
        ChainElement loopMapper = getElementById(LOOP_INSIDE_MAPPER_ID);
        ChainElement script1 = getElementById(SCRIPT_1_ID);
        ChainElement script1Mapper1 = getElementById(SCRIPT_1_MAPPER_1_ID);
        ChainElement script1TryCatchFinally = getElementById(SCRIPT_1_TRY_CATCH_FINALLY_ID);
        ChainElement loop = getElementById(LOOP_ID);
        when(elementRepository.findById(eq(LOOP_INSIDE_MAPPER_ID))).thenReturn(Optional.of(loopMapper));
        when(elementRepository.findById(eq(SCRIPT_1_ID))).thenReturn(Optional.of(script1));
        when(dependencyRepository.findByFromAndTo(LOOP_INSIDE_MAPPER_ID, SCRIPT_1_ID)).thenReturn(Optional.empty());
        when(elementRepository.save(eq(script1))).thenReturn(script1);
        when(elementRepository.save(eq(script1Mapper1))).thenReturn(script1Mapper1);
        when(elementRepository.save(eq(script1TryCatchFinally))).thenReturn(script1TryCatchFinally);

        ChainDiff chainDiff = dependencyService.create(LOOP_INSIDE_MAPPER_ID, SCRIPT_1_ID);

        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getRemovedDependencies(), empty());
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), hasSize(3));
        assertThat(chainDiff.getRemovedElements(), empty());

        Dependency dependency = chainDiff.getCreatedDependencies().get(0);

        assertThat(dependency.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(dependency.getElementFrom(), equalTo(loopMapper));
        assertThat(dependency.getElementTo(), equalTo(script1));
        assertThat(loopMapper.getInputDependencies(), empty());
        assertThat(loopMapper.getOutputDependencies(), hasSize(1));
        assertThat(loopMapper.getOutputDependencies(), Matchers.contains(dependency));
        assertThat(script1.getInputDependencies(), hasSize(1));
        assertThat(script1.getOutputDependencies(), hasSize(2));
        assertThat(script1.getInputDependencies(), Matchers.contains(dependency));
        assertThat(chainDiff.getUpdatedElements(), Matchers.contains(script1, script1Mapper1, script1TryCatchFinally));
        assertThat(loopMapper.getParent(), equalTo(loop));
        assertThat(script1.getParent(), equalTo(loop));
        assertThat(script1Mapper1.getParent(), equalTo(loop));
        assertThat(script1TryCatchFinally.getParent(), equalTo(loop));
        verify(elementRepository, times(3)).save(any());
        verify(elementRepository, times(1)).save(eq(script1));
        verify(elementRepository, times(1)).save(eq(script1Mapper1));
        verify(elementRepository, times(1)).save(eq(script1TryCatchFinally));
        verify(dependencyRepository, times(1)).save(eq(dependency));
    }

    @DisplayName("Creating dependency between container child and old style container")
    @Test
    public void createFromContainerChildToOldStyleContainerElementTest() {
        ChainElement loopElement = getElementById(LOOP_ID);
        ChainElement asyncSplit = getElementById(ASYNC_SPLIT_ID);
        ChainElement asyncSplitElementScript = getElementById(ASYNC_SPLIT_ELEMENT_SCRIPT_ID);
        ChainElement syncSplitElementMapper = getElementById(SYNC_SPLIT_ELEMENT_MAPPER_ID);
        ChainElement syncSplitElementScript = getElementById(SYNC_SPLIT_ELEMENT_SCRIPT_ID);
        ChainElement parentIfElement = getElementById(IF_ID);
        when(elementRepository.findById(eq(LOOP_ID))).thenReturn(Optional.of(loopElement));
        when(elementRepository.findById(eq(ASYNC_SPLIT_ID))).thenReturn(Optional.of(asyncSplit));
        when(dependencyRepository.findByFromAndTo(eq(LOOP_ID), eq(ASYNC_SPLIT_ID))).thenReturn(Optional.empty());
        when(elementRepository.save(eq(asyncSplit))).thenReturn(asyncSplit);
        when(elementRepository.save(eq(asyncSplitElementScript))).thenReturn(asyncSplitElementScript);
        when(elementRepository.save(eq(syncSplitElementMapper))).thenReturn(syncSplitElementMapper);
        when(elementRepository.save(eq(syncSplitElementScript))).thenReturn(syncSplitElementScript);

        ChainDiff chainDiff = dependencyService.create(LOOP_ID, ASYNC_SPLIT_ID);

        assertThat(chainDiff.getCreatedDependencies(), hasSize(1));
        assertThat(chainDiff.getRemovedDependencies(), empty());
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), hasSize(4));
        assertThat(chainDiff.getRemovedElements(), empty());

        Dependency dependency = chainDiff.getCreatedDependencies().get(0);

        assertThat(dependency.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(dependency.getElementFrom(), equalTo(loopElement));
        assertThat(dependency.getElementTo(), equalTo(asyncSplit));
        assertThat(loopElement.getInputDependencies(), empty());
        assertThat(loopElement.getOutputDependencies(), hasSize(1));
        assertThat(loopElement.getOutputDependencies(), Matchers.contains(dependency));
        assertThat(asyncSplit.getInputDependencies(), hasSize(1));
        assertThat(asyncSplit.getOutputDependencies(), empty());
        assertThat(asyncSplit.getInputDependencies(), Matchers.contains(dependency));
        assertThat(
                chainDiff.getUpdatedElements(),
                Matchers.containsInAnyOrder(asyncSplit, asyncSplitElementScript, syncSplitElementMapper, syncSplitElementScript)
        );
        assertThat(loopElement.getParent(), equalTo(parentIfElement));
        assertThat(asyncSplit.getParent(), equalTo(parentIfElement));
        assertThat(asyncSplitElementScript.getParent(), equalTo(parentIfElement));
        assertThat(syncSplitElementMapper.getParent(), equalTo(parentIfElement));
        assertThat(syncSplitElementScript.getParent(), equalTo(parentIfElement));
        verify(elementRepository, times(4)).save(any());
        verify(elementRepository, times(1)).save(eq(asyncSplit));
        verify(elementRepository, times(1)).save(eq(asyncSplitElementScript));
        verify(elementRepository, times(1)).save(eq(syncSplitElementMapper));
        verify(elementRepository, times(1)).save(eq(syncSplitElementScript));
        verify(dependencyRepository, times(1)).save(eq(dependency));
    }

    private static Stream<Arguments> createDependencyTestDataExceptionalCase() {
        return Stream.of(
                Arguments.of(
                        "Between root element and element with disabled input",
                        HTTP_TRIGGER_SCRIPT_ID,
                        HTTP_TRIGGER_ID
                ),
                Arguments.of(
                        "To group container",
                        IF_SCRIPT_ID,
                        GROUP_CONTAINER_ID
                ),
                Arguments.of(
                        "From group container",
                        GROUP_CONTAINER_ID,
                        IF_SCRIPT_ID
                ),
                Arguments.of(
                        "Between container child and group container child",
                        LOOP_INSIDE_MAPPER_ID,
                        GROUP_CONTAINER_SCRIPT_ID
                ),
                Arguments.of(
                        "Between container child and element with input dependencies",
                        LOOP_INSIDE_MAPPER_ID,
                        SCRIPT_1_MAPPER_1_ID
                ),
                Arguments.of(
                        "Between container child and elements chain with at least one element with multiple input dependencies",
                        LOOP_ID,
                        SCRIPT_2_ID
                ),
                Arguments.of(
                        "Dependency to the only one container start element",
                        SCRIPT_1_TRY_INSIDE_SCRIPT_ID,
                        SCRIPT_1_TRY_INSIDE_MAPPER_ID
                )
        );
    }

    @DisplayName("Creating dependency. Exceptional case")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("createDependencyTestDataExceptionalCase")
    public void createDependencyTestExceptionalCase(String scenario, String fromId, String toId) {
        ChainElement elementFrom = getElementById(fromId);
        ChainElement elementTo = getElementById(toId);
        when(elementRepository.findById(eq(fromId))).thenReturn(Optional.of(elementFrom));
        when(elementRepository.findById(eq(toId))).thenReturn(Optional.of(elementTo));

        assertThrows(DependencyValidationException.class, () -> dependencyService.create(fromId, toId));
    }

    @DisplayName("Creating a dependency that already exists")
    @Test
    public void createAlreadyExistedDependencyTestExceptionalCase() {
        ChainElement elementFrom = getElementById(SCRIPT_1_TRY_INSIDE_MAPPER_ID);
        ChainElement elementTo = getElementById(SCRIPT_1_TRY_INSIDE_SCRIPT_ID);
        when(elementRepository.findById(eq(SCRIPT_1_TRY_INSIDE_MAPPER_ID))).thenReturn(Optional.of(elementFrom));
        when(elementRepository.findById(eq(SCRIPT_1_TRY_INSIDE_SCRIPT_ID))).thenReturn(Optional.of(elementTo));
        when(dependencyRepository.findByFromAndTo(eq(SCRIPT_1_TRY_INSIDE_MAPPER_ID), eq(SCRIPT_1_TRY_INSIDE_SCRIPT_ID)))
                .thenReturn(Optional.of(elementFrom.getOutputDependencies().get(0)));

        assertThrows(
                EntityExistsException.class,
                () -> dependencyService.create(SCRIPT_1_TRY_INSIDE_MAPPER_ID, SCRIPT_1_TRY_INSIDE_SCRIPT_ID)
        );
    }

    @DisplayName("Deleting dependency by id")
    @Test
    public void deleteByIdTest() {
        Dependency dependency = getElementById(SCRIPT_1_TRY_INSIDE_MAPPER_ID).getOutputDependencies().get(0);
        when(dependencyRepository.getReferenceById(eq(dependency.getId()))).thenReturn(dependency);
        doNothing().when(dependencyRepository).deleteById(eq(dependency.getId()));

        ChainDiff chainDiff = dependencyService.deleteById(dependency.getId());

        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), hasSize(1));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getRemovedDependencies(), Matchers.contains(dependency));
        verify(dependencyRepository, times(1)).deleteById(eq(dependency.getId()));
    }

    @DisplayName("Deleting dependencies by ids")
    @Test
    public void deleteAllByIdsTest() {
        Dependency dependency1 = getElementById(SCRIPT_2_MAPPER_SCRIPT_ID).getInputDependencies().get(0);
        Dependency dependency2 = getElementById(SCRIPT_2_MAPPER_SCRIPT_ID).getInputDependencies().get(1);
        Dependency dependency3 = getElementById(SCRIPT_2_ID).getOutputDependencies().get(0);
        dependency1.setId("9699c862-d394-4e47-82a8-55611e004cac");
        dependency2.setId("0a6ffff4-a399-496d-9f81-7603403627ee");
        dependency3.setId("adacd00e-9a30-460e-a012-1c2c3fe158d4");
        List<Dependency> dependencies = List.of(dependency1, dependency2, dependency3);
        List<String> dependencyIds = dependencies.stream().map(Dependency::getId).collect(Collectors.toList());
        when(dependencyRepository.findAllById(eq(dependencyIds))).thenReturn(dependencies);
        doNothing().when(dependencyRepository).deleteAllById(eq(dependencyIds));

        ChainDiff chainDiff = dependencyService.deleteAllByIds(dependencyIds);

        assertThat(chainDiff.getCreatedDependencies(), empty());
        assertThat(chainDiff.getRemovedDependencies(), hasSize(dependencyIds.size()));
        assertThat(chainDiff.getCreatedElements(), empty());
        assertThat(chainDiff.getUpdatedElements(), empty());
        assertThat(chainDiff.getRemovedElements(), empty());
        assertThat(chainDiff.getRemovedDependencies(), equalTo(dependencies));
        verify(dependencyRepository, times(1)).deleteAllById(eq(dependencyIds));
    }

    private ChainElement getElementById(String id) {
        return Optional.ofNullable(elements.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Element " + id + " not found in test data"));
    }
}
