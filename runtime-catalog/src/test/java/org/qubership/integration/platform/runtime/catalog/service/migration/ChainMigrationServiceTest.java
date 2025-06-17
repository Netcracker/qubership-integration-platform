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

package org.qubership.integration.platform.runtime.catalog.service.migration;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.lang3.RandomStringUtils;
import org.json.JSONException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.MapperInterpretatorHelper;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.DependencyRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.service.migration.element.ElementMigration;
import org.qubership.integration.platform.runtime.catalog.service.migration.element.MigrationContext;
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
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@DisplayName("Chain migration service")
@ContextConfiguration(classes = {
        TestConfig.class,
        ChainMigrationServiceTest.TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryResourceLoader.class,
        LibraryElementsService.class,
        ChainElementsMapper.class,
        ChainMapper.class,
        ChainMigrationService.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class ChainMigrationServiceTest {

    private static final UUID UUID_VALUE = UUID.fromString("d2ecaeed-9249-40dd-b92d-7e0440622794");

    private static MockedStatic<Dependency> mockedDependency;
    private static MockedStatic<UUID> mockedUUID;

    @Autowired
    private YAMLMapper defaultYamlMapper;
    @Autowired
    private ChainMapper chainMapper;
    @Autowired
    private ChainMigrationService chainMigrationService;
    @MockBean
    private ChainRepository chainRepository;
    @MockBean
    private ElementRepository elementRepository;
    @MockBean
    private DependencyRepository dependencyRepository;
    @MockBean
    private ActionsLogService actionsLogService;
    @MockBean
    private AuditingHandler jpaAuditingHandler;
    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;

    @Autowired
    private List<ElementMigration> elemenMigrationList;

    private Map<String, ElementMigration> elementMigrations;

    @ComponentScan(basePackages = "org.qubership.integration.platform.runtime.catalog.service.migration.element")
    static class TestConfig {}

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

    @BeforeEach
    public void initializeBeforeEach() {
        elementMigrations = elemenMigrationList.stream()
                .collect(Collectors.toMap(ElementMigration::getOldElementType, Function.identity()));
    }

    private static Stream<Arguments> containsDeprecatedContainersTestData() {
        return Stream.of(
                Arguments.of(
                        "Contains one",
                        "/testData/input/service/migration/chain_with_one_deprecated_container.yaml",
                        true
                ),
                Arguments.of(
                        "Contains all",
                        "/testData/input/service/migration/chain_with_all_deprecated_containers.yaml",
                        true
                ),
                Arguments.of(
                        "Does not contain",
                        "/testData/input/service/migration/chain_without_deprecated_containers.yaml",
                        false
                )
        );
    }

    @DisplayName("Checking whether chain contains deprecated containers")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("containsDeprecatedContainersTestData")
    public void containsDeprecatedContainersTest(String scenario, String inputPath, boolean expectedResult) throws IOException {
        Chain chain = chainMapper.toEntity(defaultYamlMapper.readValue(TestUtils.getResourceFileContent(inputPath), ChainImportDTO.class));

        assertThat(chainMigrationService.containsDeprecatedContainers(chain), equalTo(expectedResult));
    }

    private static Stream<Arguments> migrateChainTestData() {
        return Stream.of(
                Arguments.of(
                        "Basic scenario",
                        "/testData/input/service/migration/basic_scenario.yaml",
                        "/testData/output/service/migration/basic_scenario.yaml",
                        false
                ),
                Arguments.of(
                        "Reuse scenario",
                        "/testData/input/service/migration/reuse_scenario.yaml",
                        "/testData/output/service/migration/reuse_scenario.yaml",
                        true
                ),
                Arguments.of(
                        "Circular dependency scenario",
                        "/testData/input/service/migration/circular_dependency_scenario.yaml",
                        "/testData/output/service/migration/circular_dependency_scenario.yaml",
                        false
                ),
                Arguments.of(
                        "Circular dependency without start element scenario",
                        "/testData/input/service/migration/circular_without_start_element_scenario.yaml",
                        "/testData/output/service/migration/circular_without_start_element_scenario.yaml",
                        false
                )
        );
    }

    @DisplayName("Migrating chain")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("migrateChainTestData")
    public void migrateChainTest(String scenario, String inputPath, String outputPath, boolean groupsRemoved) throws IOException, JSONException {
        String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent(outputPath), ChainImportDTO.class
        ));

        Chain chain = chainMapper.toEntity(defaultYamlMapper.readValue(TestUtils.getResourceFileContent(inputPath), ChainImportDTO.class));
        MigrationContext context = new MigrationContext(elementMigrations);
        Chain migratedChain = chainMigrationService.getMigratedChain(chain, context);
        String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(migratedChain));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
        assertThat(!context.getGroupsToDelete().isEmpty(), equalTo(groupsRemoved));
    }
}
