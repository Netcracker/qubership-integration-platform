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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.qubership.integration.platform.runtime.catalog.service.migration.element.MigrationContext.*;

@DisplayName("Restricted container migration")
@ContextConfiguration(classes = {
        TestConfig.class,
        RestrictedContainerMigrationTest.TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryResourceLoader.class,
        LibraryElementsService.class,
        ChainElementsMapper.class,
        ChainMapper.class
})
@ExtendWith(SpringExtension.class)
@ExtendWith(MockitoExtension.class)
public class RestrictedContainerMigrationTest {

    private static final UUID UUID_VALUE = UUID.fromString("9b6d86be-ff6e-40dd-a0fd-86bbd658faa5");

    private static MockedStatic<Dependency> mockedDependency;
    private static MockedStatic<UUID> mockedUUID;

    @MockBean
    MapperInterpretatorHelper mapperInterpretatorHelper;

    @Autowired
    private YAMLMapper defaultYamlMapper;
    @Autowired
    private LibraryResourceLoader libraryResourceLoader;
    @Autowired
    private ChainMapper chainMapper;
    private final Map<String, ElementMigration> elementMigrations;

    @ComponentScan(basePackages = "org.qubership.integration.platform.runtime.catalog.service.migration.element")
    static class TestConfig {
    }

    @Autowired
    public RestrictedContainerMigrationTest(List<ElementMigration> elementMigrations) {
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

    private static Stream<Arguments> migrateTestData() {
        return Stream.of(
                Arguments.of(
                        OLD_TRY_CATCH_FINALLY_TYPE,
                        "/testData/input/service/migration/element/try_catch_finally.yaml",
                        "/testData/output/service/migration/element/try_catch_finally.yaml"
                ),
                Arguments.of(
                        OLD_CHOICE_TYPE,
                        "/testData/input/service/migration/element/choice.yaml",
                        "/testData/output/service/migration/element/choice.yaml"
                ),
                Arguments.of(
                        OLD_CIRCUIT_BREAKER_TYPE,
                        "/testData/input/service/migration/element/circuit_breaker.yaml",
                        "/testData/output/service/migration/element/circuit_breaker.yaml"
                ),
                Arguments.of(
                        OLD_SPLIT_TYPE,
                        "/testData/input/service/migration/element/split.yaml",
                        "/testData/output/service/migration/element/split.yaml"
                ),
                Arguments.of(
                        OLD_SPLIT_ASYNC_TYPE,
                        "/testData/input/service/migration/element/split_async.yaml",
                        "/testData/output/service/migration/element/split_async.yaml"
                )
        );
    }

    @DisplayName("Migrating restricted container element")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("migrateTestData")
    public void migrateTest(String elementType, String inputPath, String outputPath) throws IOException, JSONException {
        String expected = TestUtils.OBJECT_MAPPER.writeValueAsString(defaultYamlMapper.readValue(
                TestUtils.getResourceFileContent(outputPath), ChainImportDTO.class
        ));

        ChainImportDTO chainImportDTO = defaultYamlMapper.readValue(TestUtils.getResourceFileContent(inputPath), ChainImportDTO.class);
        Chain chain = chainMapper.toEntity(chainImportDTO);
        ChainElement elementToMigrate = TestUtils.findElementInChain(chain, elementType);
        MigrationContext context = new MigrationContext(elementMigrations);

        ChainElement actualElement = elementMigrations.get(elementType).migrate(elementToMigrate, context);
        chain.getElements().replaceAll(element -> elementType.equals(element.getType()) ? actualElement : element);
        chain.removeElements(context.getElementsToDelete());
        String actual = TestUtils.OBJECT_MAPPER.writeValueAsString(chainMapper.toDto(chain));

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }
}
