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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;

/**
 * Presents a JPA {@link SpecificationSource} as a {@link CodegenSpecificationSource} for the
 * DTO-library code generators.
 */
public class SpecificationSourceCodegenAdapter implements CodegenSpecificationSource {
    private final SpecificationSource source;

    public SpecificationSourceCodegenAdapter(SpecificationSource source) {
        this.source = source;
    }

    @Override
    public String getName() {
        return source.getName();
    }

    @Override
    public String getSource() {
        return source.getSource();
    }
}
