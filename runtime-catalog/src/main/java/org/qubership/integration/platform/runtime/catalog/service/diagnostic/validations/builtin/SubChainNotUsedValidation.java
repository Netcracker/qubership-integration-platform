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

package org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.builtin;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.catalog.persistence.configs.repository.diagnostic.ElementValidationRepository;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class SubChainNotUsedValidation extends BuiltinValidation {
    private static final String MIN_USAGE_COUNT_KEY = "minUsageCount";

    private final ElementValidationRepository elementRepository;

    public SubChainNotUsedValidation(ElementValidationRepository elementRepository) {
        super(
                "sub-chain-not-used_4NIY0ODV",
                "Sub-chain is used by single chain or not used at all",
                "Rule allows to find chains which were not used in other chains, or they were used only once.",
                "Sub-chains are designed to handle frequent and common scenarios and are expected to be linked"
                        + " with large numbers of chains. Verify that sub-chains are properly linked to all chains,"
                        + " where they are supposed to be used. Consider including sub-chain logic into the main chain"
                        + " if only this chain uses it.",
                ValidationEntityType.CHAIN_ELEMENT,
                ValidationImplementationType.BUILT_IN,
                ValidationSeverity.ERROR
        );
        this.elementRepository = elementRepository;

        putProperty(MIN_USAGE_COUNT_KEY, 2);
    }

    @Override
    public Collection<ValidationChainAlert> validate() throws DiagnosticValidationUnexpectedException {
        try {
            return processValidation();
        } catch (Exception e) {
            throw new DiagnosticValidationUnexpectedException("Validation failed with an unexpected error: " + e.getMessage(), e);
        }
    }

    private @NotNull List<ValidationChainAlert> processValidation() {
        List<ChainElement> containsScriptElements =
                elementRepository.findAllForSubChainNotUsedValidation((Integer) getProperty(MIN_USAGE_COUNT_KEY));
        return containsScriptElements.stream()
                .map(element -> ValidationChainAlert.builder()
                        .validationId(getId())
                        .chain(element.getChain())
                        .element(element)
                        .build())
                .toList();
    }
}
