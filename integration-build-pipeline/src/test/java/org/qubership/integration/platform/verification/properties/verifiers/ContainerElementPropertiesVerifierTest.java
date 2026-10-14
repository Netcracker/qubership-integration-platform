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

package org.qubership.integration.platform.verification.properties.verifiers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.verification.properties.VerificationError;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ContainerElementPropertiesVerifier}: it reports an "empty container" error only when
 * the mandatory-inner-element rule fails, and names the offending element, qualifying it with its
 * parent when the descriptor carries a parent restriction.
 */
@ExtendWith(MockitoExtension.class)
class ContainerElementPropertiesVerifierTest {

    @Mock
    private LibraryElementsService libraryService;
    @Mock
    private MandatoryPropertyVerificationHelper mandatoryPropertyVerificationHelper;
    @Mock
    private ElementDescriptor descriptor;

    private ContainerElementPropertiesVerifier verifier() {
        return new ContainerElementPropertiesVerifier(libraryService, mandatoryPropertyVerificationHelper);
    }

    private static ElementImpl container(String name) {
        ElementImpl element = new ElementImpl();
        element.setType("container");
        element.setName(name);
        element.setContainer(true);
        return element;
    }

    @Test
    void appliesToEveryElement() {
        assertThat(verifier().applicableTo(container("Any"))).isTrue();
    }

    @Test
    void reportsNoErrorWhenTheContainerHasItsMandatoryInnerElement() {
        Element element = container("Filter");
        when(mandatoryPropertyVerificationHelper.isMandatoryInnerElementPresent(element)).thenReturn(true);

        assertThat(verifier().verify(element)).isEmpty();
    }

    @Test
    void reportsAnEmptyContainerErrorNamedByTheElementWhenNoParentRestriction() {
        Element element = container("Filter");
        when(mandatoryPropertyVerificationHelper.isMandatoryInnerElementPresent(element)).thenReturn(false);
        when(libraryService.lookupElementDescriptor("container")).thenReturn(Optional.of(descriptor));
        when(descriptor.getParentRestriction()).thenReturn(List.of());

        assertThat(verifier().verify(element))
                .extracting(VerificationError::message)
                .containsExactly("Container element 'Filter' must not be empty.");
    }

    @Test
    void reportsAnEmptyContainerErrorQualifiedByParentWhenTheDescriptorRestrictsTheParent() {
        ElementImpl element = container("When");
        ElementImpl parent = new ElementImpl();
        parent.setContainer(true);
        parent.setName("Choice");
        element.setParent(parent);
        when(mandatoryPropertyVerificationHelper.isMandatoryInnerElementPresent(element)).thenReturn(false);
        when(libraryService.lookupElementDescriptor("container")).thenReturn(Optional.of(descriptor));
        when(descriptor.getParentRestriction()).thenReturn(List.of("choice"));

        assertThat(verifier().verify(element))
                .extracting(VerificationError::message)
                .containsExactly("Container element 'Choice -> When' must not be empty.");
    }
}
