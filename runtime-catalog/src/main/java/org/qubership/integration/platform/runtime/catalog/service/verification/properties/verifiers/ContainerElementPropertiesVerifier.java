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

import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.ElementPropertiesVerifier;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;

@Component
public class ContainerElementPropertiesVerifier implements ElementPropertiesVerifier {

    private final LibraryElementsService libraryService;
    private final ElementUtils elementUtils;
    private static final String INNER_ELEMENT_NOT_FOUND_MESSAGE_FORMAT = "Container element '%s' must not be empty.";

    @Autowired
    public ContainerElementPropertiesVerifier(LibraryElementsService libraryService, ElementUtils elementUtils) {
        this.libraryService = libraryService;
        this.elementUtils = elementUtils;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return true;
    }

    @Override
    public Collection<VerificationError> verify(ChainElement element) {
        Collection<VerificationError> verificationErrors = new ArrayList<>();
        if (!elementUtils.isMandatoryInnerElementPresent(element)) {
            verificationErrors.add(
                    new VerificationError(String.format(
                            INNER_ELEMENT_NOT_FOUND_MESSAGE_FORMAT,
                            extractElementName(element)
                    ))
            );
        }

        return verificationErrors;
    }

    private String extractElementName(ChainElement element) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(element.getType());
        if (descriptor != null && CollectionUtils.isNotEmpty(descriptor.getParentRestriction())
                && element.getParent() != null) {
            return element.getParent().getName() + " -> " + element.getName();
        }
        return element.getName();
    }
}
