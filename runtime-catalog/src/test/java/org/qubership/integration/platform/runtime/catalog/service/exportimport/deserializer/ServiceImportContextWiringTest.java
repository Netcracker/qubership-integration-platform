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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SpecificationGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the service-import bean graph wires without a full application context.
 *
 * <p>{@link ServiceDeserializer} maps an imported system to its JPA entities through the library
 * {@link IntegrationSystemReader} and the three catalog service mappers, which in turn pull in the
 * migration services and a YAML mapper. Every collaborator is resolved by constructor injection, so a
 * missing or misnamed bean compiles but fails only at boot. This test loads the real graph — no mocks
 * for the wiring under test — and confirms the context starts with each bean present exactly once.
 *
 * <p>The strategy, revert-migration, and file-migration collections are empty here because the test
 * exercises wiring rather than a migration run; {@code qip.export.legacy-format} carries no default,
 * so it is supplied the way the application config supplies it at boot.
 */
class ServiceImportContextWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("qip.export.legacy-format=false")
            .withBean("defaultYamlMapper", YAMLMapper.class, YAMLMapper::new)
            .withBean(VersionsGetterService.class)
            .withBean(FileMigrationService.class)
            .withBean(IntegrationSystemReader.class)
            .withBean(IntegrationSystemDtoMapper.class)
            .withBean(SpecificationGroupDtoMapper.class)
            .withBean(SystemModelDtoMapper.class)
            .withBean(ServiceDeserializer.class);

    @Test
    void contextStartsWithServiceDeserializerGraph() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ServiceDeserializer.class);
            assertThat(context).hasSingleBean(IntegrationSystemReader.class);
            assertThat(context).hasSingleBean(IntegrationSystemDtoMapper.class);
            assertThat(context).hasSingleBean(SpecificationGroupDtoMapper.class);
            assertThat(context).hasSingleBean(SystemModelDtoMapper.class);
        });
    }
}
