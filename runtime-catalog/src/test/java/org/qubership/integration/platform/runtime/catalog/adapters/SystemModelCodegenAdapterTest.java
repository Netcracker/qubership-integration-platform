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

package org.qubership.integration.platform.runtime.catalog.adapters;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.codegen.model.CodegenSpecificationSource;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link SystemModelCodegenAdapter}: it exposes the model id and name, resolves the system and
 * group names through the specification-group chain, maps the persistence protocol to the library
 * enum by name (leaving a missing protocol null), and wraps each specification source.
 */
class SystemModelCodegenAdapterTest {

    @Test
    void presentsTheModelSystemGroupAndSourcesForCodegen() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id("sys-1")
                .name("Orders System")
                .protocol(org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol.HTTP)
                .build();
        SpecificationGroup group = SpecificationGroup.builder().id("grp-1").build();
        group.setName("Orders Group");
        group.setSystem(system);
        SpecificationSource source = SpecificationSource.builder().id("src-1").build();
        source.setName("orders.yaml");
        SystemModel model = SystemModel.builder()
                .id("model-1")
                .name("v1")
                .specificationGroup(group)
                .build();
        model.getSpecificationSources().add(source);

        SystemModelCodegenAdapter adapter = new SystemModelCodegenAdapter(model);

        assertThat(adapter.getId()).isEqualTo("model-1");
        assertThat(adapter.getName()).isEqualTo("v1");
        assertThat(adapter.getSystemName()).isEqualTo("Orders System");
        assertThat(adapter.getGroupName()).isEqualTo("Orders Group");
        assertThat(adapter.getProtocol()).isEqualTo(OperationProtocol.HTTP);
        assertThat(adapter.getSpecificationSources())
                .extracting(CodegenSpecificationSource::getName)
                .containsExactly("orders.yaml");
    }

    @Test
    void getProtocolReturnsNullWhenTheSystemHasNoProtocol() {
        IntegrationSystem system = IntegrationSystem.builder().id("sys-1").name("Orders System").build();
        SpecificationGroup group = SpecificationGroup.builder().id("grp-1").build();
        group.setSystem(system);
        SystemModel model = SystemModel.builder().id("model-1").specificationGroup(group).build();

        SystemModelCodegenAdapter adapter = new SystemModelCodegenAdapter(model);

        assertThat(adapter.getProtocol()).isNull();
        assertThat(adapter.getSpecificationSources()).isEmpty();
    }
}
