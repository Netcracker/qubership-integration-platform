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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.MapperInterpretatorHelper;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.IsIterableContaining.hasItem;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.qubership.integration.platform.runtime.catalog.service.migration.element.MigrationContext.*;

@DisplayName("Reused element migration")
@ContextConfiguration(classes = {
        TestConfig.class,
        ReusedElementMigrationTest.TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryResourceLoader.class,
        LibraryElementsService.class,
        ChainElementsMapper.class,
        ChainMapper.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class ReusedElementMigrationTest {

    private static final UUID UUID_VALUE = UUID.fromString("41cb70d0-d3c3-4256-ad10-22ad17b40efd");

    private static MockedStatic<Dependency> mockedDependency;
    private static MockedStatic<UUID> mockedUUID;

    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;

    @Autowired
    private YAMLMapper defaultYamlMapper;
    @Autowired
    private LibraryResourceLoader libraryResourceLoader;
    @Autowired
    private LibraryElementsService libraryService;
    @Autowired
    private ChainMapper chainMapper;
    private final Map<String, ElementMigration> elementMigrations;

    @ComponentScan(basePackages = "org.qubership.integration.platform.runtime.catalog.service.migration.element")
    static class TestConfig {}

    public record MigrationResult(int reuseElementsCount, int expectedReuseSaveCalls, int expectedReferenceSaveCalls) {}

    @Autowired
    public ReusedElementMigrationTest(List<ElementMigration> elementMigrations) {
        this.elementMigrations = elementMigrations.stream()
                .collect(Collectors.toMap(ElementMigration::getOldElementType, Function.identity()));
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

    @DisplayName("Checking whether an element can be migrated")
    @Test
    public void canBeMigratedTest() {
        boolean canBeMigrated = elementMigrations.get(REUSED_ELEMENT)
                .canBeMigrated(new ChainElement(), new MigrationContext(elementMigrations));

        assertTrue(canBeMigrated);
    }

    @DisplayName("Creating reuse element")
    @Test
    public void createReuseElementTest() throws IOException {
        Chain chain = chainMapper.toEntity(defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent("/testData/input/service/migration/element/reuse/script_element.yaml"),
                ChainImportDTO.class
        ));
        ChainElement scriptElement = chain.getElements().get(0);
        ContainerChainElement reuseElement = ((ReusedElementMigration) elementMigrations.get(REUSED_ELEMENT))
                .createReuseElement(scriptElement);

        assertThat(reuseElement.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(reuseElement.getType(), equalTo(REUSE_ELEMENT_TYPE));
        assertThat(reuseElement.getChain(), equalTo(chain));
    }

    @DisplayName("Creating reference element")
    @Test
    public void createReferenceElementTest() throws IOException {
        ElementDescriptor referenceDescriptor = libraryService.getElementDescriptor(REUSE_REFERENCE_ELEMENT_TYPE);
        Chain chain = chainMapper.toEntity(defaultYamlMapper.readValue(

                TestUtils.getResourceFileContent("/testData/input/service/migration/element/reuse/reuse_element.yaml"),
                ChainImportDTO.class
        ));
        ChainElement reuseElement = TestUtils.findElementInChain(chain, REUSE_ELEMENT_TYPE);
        ChainElement referenceElement = ((ReusedElementMigration) elementMigrations.get(REUSED_ELEMENT))
                .createReferenceElement(reuseElement);

        assertThat(referenceElement.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(referenceElement.getType(), equalTo(referenceDescriptor.getName()));
        assertThat(referenceElement.getName(), equalTo(referenceDescriptor.getTitle()));
        assertThat(referenceElement.getChain(), equalTo(chain));
        assertThat(referenceElement.getProperties().get(REUSE_ELEMENT_ID), equalTo(reuseElement.getId()));
    }

    private static Stream<Arguments> migrateTestData() {
        return Stream.of(
                Arguments.of(
                        "Basic scenario",
                        "script",
                        "/testData/input/service/migration/element/reuse/script_element.yaml",
                        "/testData/output/service/migration/element/reuse/basic_scenario.yaml",
                        new MigrationResult(1, 1, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain)
                                -> new MigrationContext(elementMigrations)
                ),
                Arguments.of(
                        "Reuse exists in context",
                        "script",
                        "/testData/input/service/migration/element/reuse/script_element.yaml",
                        "/testData/output/service/migration/element/reuse/reuse_exists.yaml",
                        new MigrationResult(1, 0, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain) -> {
                            MigrationContext context = new MigrationContext(elementMigrations);
                            ChainElement scriptElement = TestUtils.findElementInChain(chain, "script");
                            ContainerChainElement reuseElement = ContainerChainElement.builder()
                                    .id("7de4c8f8-6409-4ad8-bbc1-d2f19770bbf4")
                                    .name("Existing reuse")
                                    .type(REUSE_ELEMENT_TYPE)
                                    .chain(chain)
                                    .build();
                            reuseElement.addChildElement(scriptElement);
                            chain.addElement(reuseElement);
                            context.addReuseElement(scriptElement.getId(), reuseElement);
                            return context;
                        }
                ),
                Arguments.of(
                        "Element supporting migration",
                        "choice",
                        "/testData/input/service/migration/element/reuse/supporting_migration.yaml",
                        "/testData/output/service/migration/element/reuse/supporting_migration.yaml",
                        new MigrationResult(1, 1, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain)
                                -> new MigrationContext(elementMigrations)
                ),
                Arguments.of(
                        "Element migration in progress (recursion case)",
                        "choice",
                        "/testData/input/service/migration/element/reuse/supporting_migration.yaml",
                        "/testData/output/service/migration/element/reuse/migration_in_progress.yaml",
                        new MigrationResult(1, 1, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain) -> {
                            MigrationContext context = new MigrationContext(elementMigrations);
                            ChainElement choiceElement = TestUtils.findElementInChain(chain, "choice");
                            context.getInProgressElementIds().add(choiceElement.getId());
                            return context;
                        }
                ),
                Arguments.of(
                        "Second output element with multiple inputs",
                        "script",
                        "/testData/input/service/migration/element/reuse/second_output_multiple_inputs.yaml",
                        "/testData/output/service/migration/element/reuse/second_output_multiple_inputs.yaml",
                        new MigrationResult(2, 2, 2),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain)
                                -> new MigrationContext(elementMigrations)
                ),
                Arguments.of(
                        "Second output element has reuse in context",
                        "script",
                        "/testData/input/service/migration/element/reuse/second_output.yaml",
                        "/testData/output/service/migration/element/reuse/second_output_reuse_exists.yaml",
                        new MigrationResult(2, 1, 2),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain) -> {
                            MigrationContext context = new MigrationContext(elementMigrations);
                            ChainElement mapperElement = TestUtils.findElementInChain(chain, "mapper-2");
                            ContainerChainElement reuseElement = ContainerChainElement.builder()
                                    .id("e5d0a852-cfb3-4ae9-84a2-d3a1a3d7afb5")
                                    .name("Mapper reuse")
                                    .type(REUSE_ELEMENT_TYPE)
                                    .chain(chain)
                                    .build();
                            reuseElement.addChildElement(mapperElement);
                            chain.addElement(reuseElement);
                            context.addReuseElement(mapperElement.getId(), reuseElement);
                            return context;
                        }
                ),
                Arguments.of(
                        "Second output element migration in progress (recursion case)",
                        "script",
                        "/testData/input/service/migration/element/reuse/second_output_support_migration.yaml",
                        "/testData/output/service/migration/element/reuse/second_output_migration_in_progress.yaml",
                        new MigrationResult(1, 1, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain) -> {
                            MigrationContext context = new MigrationContext(elementMigrations);
                            ChainElement mapperElement = TestUtils.findElementInChain(chain, "split-async");
                            context.getInProgressElementIds().add(mapperElement.getId());
                            return context;
                        }
                ),
                Arguments.of(
                        "Second output element does not support migration",
                        "script",
                        "/testData/input/service/migration/element/reuse/second_output.yaml",
                        "/testData/output/service/migration/element/reuse/second_output_doesnt_migration.yaml",
                        new MigrationResult(1, 1, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain)
                                -> new MigrationContext(elementMigrations)
                ),
                Arguments.of(
                        "Second output element support migration",
                        "script",
                        "/testData/input/service/migration/element/reuse/second_output_support_migration.yaml",
                        "/testData/output/service/migration/element/reuse/second_output_support_migration.yaml",
                        new MigrationResult(1, 1, 1),
                        (BiFunction<Map<String, ElementMigration>, Chain, MigrationContext>) (elementMigrations, chain)
                                -> new MigrationContext(elementMigrations)
                )
        );
    }

    @DisplayName("Migrating reused element")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("migrateTestData")
    public void migrateTest(
            String scenario,
            String elementType,
            String inputPath,
            String outputPath,
            MigrationResult migrationResult,
            BiFunction<Map<String, ElementMigration>, Chain, MigrationContext> contextFactory
    ) throws IOException, JSONException {
        ElementDescriptor referenceDescriptor = libraryService.getElementDescriptor(REUSE_REFERENCE_ELEMENT_TYPE);
        String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent(outputPath), ChainImportDTO.class
        ));

        Chain chain = chainMapper.toEntity(defaultYamlMapper.readValue(TestUtils.getResourceFileContent(inputPath), ChainImportDTO.class));
        ChainElement chainElement = TestUtils.findElementInChain(chain, elementType);
        MigrationContext migrationContext = contextFactory.apply(elementMigrations, chain);
        ChainElement referenceElement = elementMigrations.get(REUSED_ELEMENT).migrate(chainElement, migrationContext);
        String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
        assertThat(migrationContext.getReuseElements(), aMapWithSize(migrationResult.reuseElementsCount()));
        assertThat(migrationContext.getReuseElements().keySet(), hasItem(chainElement.getId()));

        ContainerChainElement reuseElement = migrationContext.getReuseElements().get(chainElement.getId());

        assertThat(referenceElement.getId(), equalTo(UUID_VALUE.toString()));
        assertThat(referenceElement.getType(), equalTo(referenceDescriptor.getName()));
        assertThat(referenceElement.getName(), equalTo(referenceDescriptor.getTitle()));
        assertThat(referenceElement.getChain(), equalTo(chain));
        assertThat(referenceElement.getProperties().get(REUSE_ELEMENT_ID), equalTo(reuseElement.getId()));
    }
}
