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

package org.qubership.integration.platform.runtime.catalog.service.migration.element;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.lang3.RandomStringUtils;
import org.json.JSONException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.MapperInterpretatorHelper;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.qubership.integration.platform.runtime.catalog.testutils.configuration.TestConfig;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainElementsMapper;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainMapper;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ElementsDTO;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;
import static org.qubership.integration.platform.runtime.catalog.service.migration.element.MigrationContext.*;

@DisplayName("Element migration")
@ContextConfiguration(classes = {
        TestConfig.class,
        ElementMigrationTest.TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryResourceLoader.class,
        LibraryElementsService.class,
        ChainElementsMapper.class,
        ChainMapper.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class ElementMigrationTest {

    private static final UUID UUID_VALUE = UUID.fromString("2896c23a-c74d-4338-8664-e5e07d803434");

    private static MockedStatic<Dependency> mockedDependency;
    private static MockedStatic<UUID> mockedUUID;

    private static final String OLD_TEST_TYPE = "test-element";
    private static final String NEW_TEST_TYPE = "test-element-2";

    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;

    @Autowired
    private YAMLMapper defaultYamlMapper;
    @Autowired
    private LibraryResourceLoader libraryResourceLoader;
    @Autowired
    private ChainMapper chainMapper;
    @Autowired
    private ChainElementsMapper chainElementsMapper;
    private final Map<String, ElementMigration> elementMigrations;

    @ComponentScan(basePackages = "org.qubership.integration.platform.runtime.catalog.service.migration.element")
    static class TestConfig {}

    static class TestElementMigration extends ElementMigration {

        protected TestElementMigration(LibraryElementsService libraryService) {
            super(libraryService, OLD_TEST_TYPE, NEW_TEST_TYPE);
        }

        @Override
        public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
            return true;
        }

        @Override
        public ChainElement migrate(ChainElement chainElement, MigrationContext context) {
            chainElement.setType(getNewElementType());
            return chainElement;
        }
    }

    @Autowired
    public ElementMigrationTest(LibraryElementsService libraryService, List<ElementMigration> elementMigrations) {
        this.elementMigrations = elementMigrations.stream()
                .collect(Collectors.toMap(ElementMigration::getOldElementType, Function.identity()));
        this.elementMigrations.put(OLD_TEST_TYPE, new TestElementMigration(libraryService));
    }

    @BeforeAll
    public static void initializeBeforeAll() {
        mockedDependency = mockStatic(Dependency.class, CALLS_REAL_METHODS);
        mockedDependency.when(() -> Dependency.of(any(ChainElement.class), any(ChainElement.class)))
                .thenAnswer(i -> {
                    Dependency dependency = new Dependency();
                    dependency.setId(RandomStringUtils.random(12, true, true));
                    dependency.setElementFrom((ChainElement) i.getArguments()[0]);
                    dependency.setElementTo((ChainElement) i.getArguments()[1]);
                    return dependency;
                });
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        mockedUUID.when(UUID::randomUUID).thenReturn(UUID_VALUE);
    }

    @AfterAll
    public static void finalizeAfterAll() {
        mockedDependency.close();
        mockedUUID.close();
    }

    @DisplayName("Migrating next elements")
    @Test
    public void migrateNextElementsTest() throws IOException, JSONException {
        String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/output/service/migration/element/next_element_migration.yaml"),
                ChainImportDTO.class
        ));

        ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/input/service/migration/element/next_element_migration.yaml"),
                ChainImportDTO.class
        );
        Chain chain = chainMapper.toEntity(chainImportDTO);
        ChainElement nextElement = TestUtils.findElementInChain(chain, "mapper-2");
        MigrationContext migrationContext = new MigrationContext(elementMigrations);
        elementMigrations.get(OLD_TEST_TYPE).migrateNextElements(nextElement, migrationContext);
        String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @DisplayName("Removing element from parent group")
    @Test
    public void removeElementFromParentGroupIfRequiredTest() throws IOException {
        ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/input/service/migration/element/grouped_element.yaml"),
                ChainImportDTO.class
        );
        Chain chain = chainMapper.toEntity(chainImportDTO);
        ChainElement chainElement = TestUtils.findElementInChain(chain, "script");
        MigrationContext migrationContext = new MigrationContext(elementMigrations);
        elementMigrations.get(OLD_TEST_TYPE).removeElementFromParentGroupIfRequired(chainElement, migrationContext);

        assertThat(chain.getElements(), hasSize(1));
        assertThat(chain.getElements(), hasItem(chainElement));
        assertThat(chainElement.getParent(), is(nullValue()));
        assertThat(migrationContext.getGroupsToDelete(), hasSize(3));
        assertTrue(
                migrationContext.getGroupsToDelete().stream().allMatch(element -> CONTAINER.equals(element.getType())),
                "The list of groups to be deleted contains elements of non container type"
        );
    }

    @DisplayName("Building container from chain element")
    @Test
    public void buildContainerFromChainElementTest() throws IOException {
        ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/input/service/migration/element/build_container_element.yaml"),
                ChainImportDTO.class
        );
        Chain chain = chainMapper.toEntity(chainImportDTO);
        ChainElement chainElement = TestUtils.findElementInChain(chain, "catch");
        ContainerChainElement actual = elementMigrations.get(OLD_TEST_TYPE).buildContainerFromChainElement(chainElement);

        assertNotSame(actual, chainElement);
        assertThat(actual.getId(), not(equalTo(chainElement.getId())));
        assertThat(actual.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(actual.getName(), equalTo(chainElement.getName()));
        assertThat(actual.getDescription(), equalTo(chainElement.getDescription()));
        assertThat(actual.getType(), equalTo(chainElement.getType()));
        assertThat(actual.getParent(), equalTo(chainElement.getParent()));
        assertThat(actual.getProperties(), equalTo(chainElement.getProperties()));
        assertThat(actual.getChain(), equalTo(chainElement.getChain()));
        assertThat(actual.getCreatedBy(), equalTo(chainElement.getCreatedBy()));
        assertThat(actual.getCreatedWhen(), equalTo(chainElement.getCreatedWhen()));
        assertThat(actual.getModifiedBy(), equalTo(chainElement.getModifiedBy()));
        assertThat(actual.getModifiedWhen(), equalTo(chainElement.getModifiedWhen()));
        assertThat(actual.getOriginalId(), equalTo(chainElement.getOriginalId()));
        assertThat(actual.getSnapshot(), equalTo(chainElement.getSnapshot()));
        assertThat(actual.getEnvironment(), equalTo(chainElement.getEnvironment()));
    }

    @DisplayName("Replacing reused element with reference")
    @Test
    public void replaceReusedElementWithReferenceTest() throws IOException, JSONException {
        String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/output/service/migration/element/replace_reused_element.yaml"),
                ChainImportDTO.class
        ));

        ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/input/service/migration/element/replace_reused_element.yaml"),
                ChainImportDTO.class
        );
        Chain chain = chainMapper.toEntity(chainImportDTO);
        Dependency dependency = chain.getDependencies()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dependencies not found in chain"));
        MigrationContext migrationContext = new MigrationContext(elementMigrations);
        ChainElement referenceElement = elementMigrations.get(OLD_TEST_TYPE).replaceReusedElementWithReference(
                dependency.getElementFrom(),
                dependency.getElementTo(),
                migrationContext
        );
        chain.addElement(referenceElement);
        String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @DisplayName("Replacing reused element with reference with non existent reused migration object")
    @Test
    public void replaceReusedElementWithReferenceWithNonExistentReusedMigrationObjectTest() {
        ChainElement fromElement = new ChainElement();
        ChainElement toElement = new ChainElement();
        MigrationContext migrationContext = new MigrationContext(elementMigrations);
        elementMigrations.remove(REUSED_ELEMENT);

        assertThrows(
                IllegalArgumentException.class,
                () -> elementMigrations.get(OLD_TEST_TYPE).replaceReusedElementWithReference(fromElement, toElement, migrationContext)
        );
    }

    @Nested
    @DisplayName("Collecting children")
    class CollectChildren {

        @DisplayName("Basic scenario")
        @Test
        public void collectChildrenBasicScenarioTest() throws IOException, JSONException {
            String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/output/service/migration/element/collect_children/basic_scenario.yaml"),
                    ElementsDTO.class
            ));

            ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/input/service/migration/element/collect_children/basic_scenario.yaml"),
                    ChainImportDTO.class
            );
            Chain chain = chainMapper.toEntity(chainImportDTO);
            ChainElement chainElement = TestUtils.findElementInChain(chain, "try");
            MigrationContext migrationContext = new MigrationContext(elementMigrations);
            List<ChainElement> children = elementMigrations.get(OLD_TEST_TYPE).collectChildren(chainElement, migrationContext);
            String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainElementsMapper.toDto(children));

            JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
        }

        @DisplayName("Multiple inputs")
        @Test
        public void collectChildrenWithMultipleInputsTest() throws IOException, JSONException {
            String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/output/service/migration/element/collect_children/multiple_inputs.yaml"),
                    ChainImportDTO.class
            ));

            ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/input/service/migration/element/collect_children/multiple_inputs.yaml"),
                    ChainImportDTO.class
            );
            Chain chain = chainMapper.toEntity(chainImportDTO);
            ChainElement chainElement = TestUtils.findElementInChain(chain, "try");
            MigrationContext migrationContext = new MigrationContext(elementMigrations);
            List<ChainElement> children = elementMigrations.get(OLD_TEST_TYPE).collectChildren(chainElement, migrationContext);
            String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

            JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
            assertThat(children, hasSize(1));
            assertThat(children.get(0).getId(), equalTo(UUID_VALUE.toString()));
            assertThat(children.get(0).getType(), equalTo(REUSE_REFERENCE_ELEMENT_TYPE));
            assertThat(children.get(0).getProperties().get(REUSE_ELEMENT_ID), equalTo(UUID_VALUE.toString()));
        }

        @DisplayName("One reference input in progress")
        @Test
        public void collectChildrenWithOneReferenceInputInProgressTest() throws IOException, JSONException {
            String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/output/service/migration/element/collect_children/reference_in_progress.yaml"),
                    ChainImportDTO.class
            ));

            ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/input/service/migration/element/collect_children/multiple_inputs.yaml"),
                    ChainImportDTO.class
            );
            Chain chain = chainMapper.toEntity(chainImportDTO);
            ChainElement chainElement = TestUtils.findElementInChain(chain, "try");
            ChainElement mapperElement = TestUtils.findElementInChain(chain, "mapper-2");
            MigrationContext migrationContext = new MigrationContext(elementMigrations);
            migrationContext.getInProgressReferenceInputIds().add(mapperElement.getId());
            List<ChainElement> children = elementMigrations.get(OLD_TEST_TYPE).collectChildren(chainElement, migrationContext);
            String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

            JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
            assertThat(children, hasSize(1));
            assertThat(children.get(0).getId(), equalTo(UUID_VALUE.toString()));
            assertThat(children.get(0).getType(), equalTo(REUSE_REFERENCE_ELEMENT_TYPE));
            assertThat(children.get(0).getProperties().get(REUSE_ELEMENT_ID), equalTo(UUID_VALUE.toString()));
        }

        @DisplayName("Existing reuse element")
        @Test
        public void collectChildrenWithExistentReuseTest() throws IOException, JSONException {
            String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/output/service/migration/element/collect_children/existing_reuse.yaml"),
                    ChainImportDTO.class
            ));

            ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/input/service/migration/element/collect_children/existing_reuse.yaml"),
                    ChainImportDTO.class
            );
            Chain chain = chainMapper.toEntity(chainImportDTO);
            ChainElement chainElement = TestUtils.findElementInChain(chain, "script");
            ContainerChainElement reuseElement = (ContainerChainElement) TestUtils.findElementInChain(chain, "reuse");
            MigrationContext migrationContext = new MigrationContext(elementMigrations);
            migrationContext.addReuseElement(reuseElement.getElements().get(0).getId(), reuseElement);
            List<ChainElement> children = elementMigrations.get(OLD_TEST_TYPE).collectChildren(chainElement, migrationContext);
            String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

            JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
            assertThat(children, hasSize(1));
            assertThat(children.get(0).getId(), equalTo(UUID_VALUE.toString()));
            assertThat(children.get(0).getType(), equalTo(REUSE_REFERENCE_ELEMENT_TYPE));
            assertThat(children.get(0).getProperties().get(REUSE_ELEMENT_ID), equalTo(reuseElement.getId()));
        }

        @DisplayName("Element migration in progress")
        @Test
        public void collectChildrenWithElementMigrationInProgressTest() throws IOException, JSONException {
            String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/output/service/migration/element/collect_children/migration_in_progress.yaml"),
                    ChainImportDTO.class
            ));

            ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(
                    TestUtils.getResourceFileContent("/testData/input/service/migration/element/collect_children/migration_in_progress.yaml"),
                    ChainImportDTO.class
            );
            Chain chain = chainMapper.toEntity(chainImportDTO);
            ChainElement chainElement = TestUtils.findElementInChain(chain, "script");
            ChainElement mapperElement = TestUtils.findElementInChain(chain, "mapper-2");
            MigrationContext migrationContext = new MigrationContext(elementMigrations);
            migrationContext.getInProgressElementIds().add(mapperElement.getId());
            List<ChainElement> children = elementMigrations.get(OLD_TEST_TYPE).collectChildren(chainElement, migrationContext);
            String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

            JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
            assertThat(children, hasSize(1));
            assertThat(children.get(0).getId(), equalTo(UUID_VALUE.toString()));
            assertThat(children.get(0).getType(), equalTo(REUSE_REFERENCE_ELEMENT_TYPE));
            assertThat(children.get(0).getProperties().get(REUSE_ELEMENT_ID), equalTo(UUID_VALUE.toString()));
        }
    }
}
