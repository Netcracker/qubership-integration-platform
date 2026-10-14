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

import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.verification.properties.ElementPropertiesVerifier;
import org.qubership.integration.platform.verification.properties.VerificationError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;

@Component
public class ElementWithMandatoryFieldsVerifier implements ElementPropertiesVerifier {

    private final MandatoryPropertyVerificationHelper mandatoryPropertyVerificationHelper;

    @Autowired
    public ElementWithMandatoryFieldsVerifier(
        MandatoryPropertyVerificationHelper mandatoryPropertyVerificationHelper
    ) {
        this.mandatoryPropertyVerificationHelper = mandatoryPropertyVerificationHelper;
    }

    @Override
    public boolean applicableTo(Element element) {
        return true;
    }

    @Override
    public Collection<VerificationError> verify(Element element) {
        return mandatoryPropertyVerificationHelper.areMandatoryPropertiesPresent(element)
                ? Collections.emptyList()
                : Collections.singletonList(new VerificationError("Required fields not specified"));
    }
}
