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
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic.ElementValidationRepository;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class ScriptingFoundInChainValidation extends BuiltinValidation {
    private final ElementValidationRepository elementRepository;

    public ScriptingFoundInChainValidation(ElementValidationRepository elementRepository) {
        super(
                "scripting-found-in-chain_0WHKW1A3",
                "Scripting found in the chain",
                "Rule allows to find chains and chain elements which contain scripting.",
                "Scripting is not recommended, as it often causes unexpected harm to the data or instance if done incorrectly."
                        + " Please consider replacing scripts with mapper, if possible.",
                ValidationEntityType.CHAIN_ELEMENT,
                ValidationImplementationType.BUILT_IN,
                ValidationSeverity.WARNING
        );
        this.elementRepository = elementRepository;
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
        List<ChainElement> containsScriptElements = elementRepository.findAllForExcessiveScriptUsageValidation();
        return containsScriptElements.stream()
                .map(element -> ValidationChainAlert.builder()
                        .validationId(getId())
                        .chain(element.getChain())
                        .element(element)
                        .build())
                .toList();
    }
}
