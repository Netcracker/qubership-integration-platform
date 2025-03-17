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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CompoundPropertiesBuilder implements ElementPropertiesBuilder {
    private final Collection<ElementPropertiesBuilder> builders;

    public CompoundPropertiesBuilder(Collection<ElementPropertiesBuilder> builders) {
        this.builders = builders;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return builders.stream().anyMatch(builder -> builder.applicableTo(element));
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return builders.stream().map(builder -> builder.build(element)).reduce(
                Collections.emptyMap(),
                (props1, props2) -> {
                    Map<String, String> props = new HashMap<>();
                    props.putAll(props1);
                    props.putAll(props2);
                    return props;
                });
    }
}
