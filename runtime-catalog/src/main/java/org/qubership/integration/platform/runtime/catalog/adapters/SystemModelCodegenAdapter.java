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

import org.qubership.integration.platform.codegen.model.CodegenSpecificationSource;
import org.qubership.integration.platform.codegen.model.CodegenSystemModel;
import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.util.List;

/**
 * Presents a JPA {@link SystemModel} as a {@link CodegenSystemModel} for the DTO-library code
 * generators.
 *
 * <p>Resolves the owning system and group through the specification-group chain, and maps the
 * persistence {@code OperationProtocol} to the library enum by name.
 */
public class SystemModelCodegenAdapter implements CodegenSystemModel {
    private final SystemModel model;

    public SystemModelCodegenAdapter(SystemModel model) {
        this.model = model;
    }

    @Override
    public String getId() {
        return model.getId();
    }

    @Override
    public String getName() {
        return model.getName();
    }

    @Override
    public String getSystemName() {
        return getSystem().getName();
    }

    @Override
    public String getGroupName() {
        return model.getSpecificationGroup().getName();
    }

    @Override
    public OperationProtocol getProtocol() {
        var protocol = getSystem().getProtocol();
        return protocol == null ? null : OperationProtocol.valueOf(protocol.name());
    }

    @Override
    public List<CodegenSpecificationSource> getSpecificationSources() {
        return model.getSpecificationSources().stream()
                .<CodegenSpecificationSource>map(SpecificationSourceCodegenAdapter::new)
                .toList();
    }

    private IntegrationSystem getSystem() {
        SpecificationGroup specificationGroup = model.getSpecificationGroup();
        return specificationGroup.getSystem();
    }
}
