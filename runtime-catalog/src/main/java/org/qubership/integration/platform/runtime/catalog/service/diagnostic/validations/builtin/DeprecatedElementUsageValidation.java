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
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class DeprecatedElementUsageValidation extends BuiltinValidation {
    private final ElementRepository elementRepository;
    private final LibraryElementsService libraryElementsService;

    @Autowired
    public DeprecatedElementUsageValidation(ElementRepository elementRepository, LibraryElementsService libraryElementsService) {
        super(
                "deprecated-element_YLTU14X8",
                "Deprecated elements found in the chain",
                "Rule allows to find chains with deprecated elements.",
                "Chain contains deprecated elements. Please consider automatic migration option,"
                        + " available on the configuration graph, or manually re-configure the chain.",
                ValidationEntityType.CHAIN_ELEMENT,
                ValidationImplementationType.BUILT_IN,
                ValidationSeverity.ERROR
        );
        this.elementRepository = elementRepository;
        this.libraryElementsService = libraryElementsService;
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
        List<String> deprecatedTypes = libraryElementsService.getDeprecatedElementsNames();
        List<ChainElement> deprecatedElements = elementRepository.findAllByTypeInAndChainNotNull(deprecatedTypes);
        return deprecatedElements.stream()
                .map(element -> ValidationChainAlert.builder()
                        .validationId(getId())
                        .chain(element.getChain())
                        .element(element)
                        .build())
                .toList();
    }
}
