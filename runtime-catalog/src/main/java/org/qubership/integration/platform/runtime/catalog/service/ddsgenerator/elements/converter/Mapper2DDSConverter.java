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

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class Mapper2DDSConverter extends ElementDDSConverter {
    private static final Map<String, String> TYPE_MAPPING = Map.of(
            CamelNames.MAPPER_2, "Mapper"
    );

    @Override
    protected Map<String, String> getTypeMapping() {
        return TYPE_MAPPING;
    }

    @Override
    public TemplateChainElement convert(ChainElement element) {
        Map<String, Object> elementTemplateProps = new HashMap<>();

        TemplateChainElement.TemplateChainElementBuilder builder = getBuilder(element);
        builder.properties(elementTemplateProps);

        return builder.build();
    }
}
