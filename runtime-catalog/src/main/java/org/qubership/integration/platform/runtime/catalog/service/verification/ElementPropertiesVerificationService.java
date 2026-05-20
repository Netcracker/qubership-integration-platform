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

package org.qubership.integration.platform.runtime.catalog.service.verification;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.ElementPropertiesVerifierFactory;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ElementPropertiesVerificationService {
    private final ElementPropertiesVerifierFactory elementPropertiesVerifierFactory;

    @Autowired
    public ElementPropertiesVerificationService(ElementPropertiesVerifierFactory elementPropertiesVerifierFactory) {
        this.elementPropertiesVerifierFactory = elementPropertiesVerifierFactory;
    }

    public Collection<VerificationError> verifyProperties(ChainElement element) {
        return elementPropertiesVerifierFactory.getElementPropertiesVerifier(element).verify(element);
    }

    public Map<ChainElement, Collection<VerificationError>> verifyElementProperties(Chain chain) {
        return chain.getElements().stream()
                .map(element -> Map.entry(element, verifyProperties(element)))
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
