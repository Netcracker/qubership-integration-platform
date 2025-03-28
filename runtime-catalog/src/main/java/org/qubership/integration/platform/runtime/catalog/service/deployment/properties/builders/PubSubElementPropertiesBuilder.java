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

import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class PubSubElementPropertiesBuilder implements ElementPropertiesBuilder {
    public static final String PUBSUB_PROJECT_ID = "projectId";
    public static final String PUBSUB_DESTINATION_NAME = "destinationName";

    @Override
    public boolean applicableTo(ChainElement element) {
        return Set.of(
                CamelNames.PUBSUB_TRIGGER_COMPONENT,
                CamelNames.PUBSUB_SENDER_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return Stream.of(
            PUBSUB_PROJECT_ID,
            PUBSUB_DESTINATION_NAME
        ).collect(Collectors.toMap(Function.identity(), element::getPropertyAsString));
    }

}
