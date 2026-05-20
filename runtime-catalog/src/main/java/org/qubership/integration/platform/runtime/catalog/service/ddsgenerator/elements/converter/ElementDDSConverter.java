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

package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.converter;

import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Allow converting a chain element to a DDS template data object
 */
public abstract class ElementDDSConverter {
    public Set<String> getTypes() {
        return getTypeMapping().keySet();
    }

    public Collection<String> getTypeNames() {
        return getTypeMapping().values();
    }

    public String getTypeName(String type) {
        return getTypeMapping().getOrDefault(type, "");
    }

    protected abstract Map<String, String> getTypeMapping();

    public abstract @Nullable TemplateChainElement convert(ChainElement element);

    protected TemplateChainElement.TemplateChainElementBuilder getBuilder(ChainElement element) {
        return TemplateChainElement.builder()
                .id(element.getId())
                .name(element.getName())
                .description(element.getDescription())
                .type(element.getType())
                .typeName(getTypeName(element.getType()));
    }
}
