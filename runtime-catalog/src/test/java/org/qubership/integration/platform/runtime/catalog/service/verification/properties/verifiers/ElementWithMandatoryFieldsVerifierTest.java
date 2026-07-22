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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ElementWithMandatoryFieldsVerifier}: it applies to every element and reports a single
 * "required fields" error exactly when the mandatory-property helper says a value is missing.
 */
@ExtendWith(MockitoExtension.class)
class ElementWithMandatoryFieldsVerifierTest {

    @Mock
    private MandatoryPropertyVerificationHelper mandatoryPropertyVerificationHelper;

    private ElementWithMandatoryFieldsVerifier verifier() {
        return new ElementWithMandatoryFieldsVerifier(mandatoryPropertyVerificationHelper);
    }

    @Test
    void appliesToEveryElement() {
        assertThat(verifier().applicableTo(ChainElement.builder().type("http-trigger").build())).isTrue();
    }

    @Test
    void reportsNoErrorWhenMandatoryPropertiesArePresent() {
        ChainElement element = ChainElement.builder().type("http-trigger").build();
        when(mandatoryPropertyVerificationHelper.areMandatoryPropertiesPresent(element)).thenReturn(true);

        assertThat(verifier().verify(element)).isEmpty();
    }

    @Test
    void reportsARequiredFieldsErrorWhenAMandatoryPropertyIsMissing() {
        ChainElement element = ChainElement.builder().type("http-trigger").build();
        when(mandatoryPropertyVerificationHelper.areMandatoryPropertiesPresent(element)).thenReturn(false);

        assertThat(verifier().verify(element))
                .extracting(VerificationError::message)
                .containsExactly("Required fields not specified");
    }
}
