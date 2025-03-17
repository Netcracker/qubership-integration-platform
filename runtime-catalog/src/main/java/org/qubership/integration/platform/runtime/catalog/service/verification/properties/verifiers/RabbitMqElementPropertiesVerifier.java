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

package org.qubership.integration.platform.runtime.catalog.service.verification.properties.verifiers;

import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.ElementPropertiesVerifier;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.DEFAULT_VHOST_CLASSIFIER_NAME;

@Component
public class RabbitMqElementPropertiesVerifier implements ElementPropertiesVerifier {
    @Override
    public boolean applicableTo(ChainElement element) {
        return List.of(
                CamelNames.RABBITMQ_TRIGGER_COMPONENT,
                CamelNames.RABBITMQ_SENDER_COMPONENT,
                CamelNames.RABBITMQ_TRIGGER_2_COMPONENT,
                CamelNames.RABBITMQ_SENDER_2_COMPONENT
        ).contains(element.getType());
    }

    @Override
    public Collection<VerificationError> verify(ChainElement element) {
        String sourceType = element.getPropertyAsString(CamelOptions.CONNECTION_SOURCE_TYPE_PROP);
        String maasClassifier = Optional.ofNullable(element.getPropertyAsString(CamelOptions.MAAS_VHOST_CLASSIFIER_NAME_PROP))
                .orElse(DEFAULT_VHOST_CLASSIFIER_NAME);
        return MaasElementPropertiesVerifierHelper.verifyMaasProperties(sourceType, maasClassifier);
    }
}
