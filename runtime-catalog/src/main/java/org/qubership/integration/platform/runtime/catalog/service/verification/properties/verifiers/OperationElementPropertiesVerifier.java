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

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.ElementPropertiesVerifier;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

import static org.qubership.integration.platform.catalog.model.constant.CamelNames.MAAS_CLASSIFIER_NAME_PROP;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.DEFAULT_VHOST_CLASSIFIER_NAME;

@Component
public class OperationElementPropertiesVerifier implements ElementPropertiesVerifier {
    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return CamelNames.ASYNC_API_TRIGGER_COMPONENT.equals(type)
                || CamelNames.SERVICE_CALL_COMPONENT.equals(type);
    }

    @Override
    public Collection<VerificationError> verify(ChainElement element) {
        return Optional.ofNullable(element.getEnvironment())
                .map(environment -> verifyProperties(element, environment))
                .orElse(Collections.emptyList());
    }

    private Collection<VerificationError> verifyProperties(ChainElement element, ServiceEnvironment environment) {
        Object protocolType = element.getProperty(CamelNames.OPERATION_PROTOCOL_TYPE_PROP);
        if (CamelNames.OPERATION_PROTOCOL_TYPE_KAFKA.equals(protocolType)
                || CamelNames.OPERATION_PROTOCOL_TYPE_AMQP.equals(protocolType)) {
            String sourceType = Optional.ofNullable(environment.getSourceType()).map(String::valueOf)
                    .orElse(null);

            String maasClassifier = (String) ElementUtils.extractOperationAsyncProperties(element.getProperties())
                    .get(MAAS_CLASSIFIER_NAME_PROP);
            if (StringUtils.isEmpty(maasClassifier) && CamelNames.OPERATION_PROTOCOL_TYPE_AMQP.equals(protocolType)) {
                maasClassifier = DEFAULT_VHOST_CLASSIFIER_NAME;
            }
            return MaasElementPropertiesVerifierHelper.verifyMaasProperties(sourceType, maasClassifier);
        } else {
            return Collections.emptyList();
        }
    }
}
