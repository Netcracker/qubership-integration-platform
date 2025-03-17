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

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.ElementPropertiesVerifier;
import org.qubership.integration.platform.runtime.catalog.service.verification.properties.VerificationError;

import java.util.Collection;
import java.util.stream.Collectors;

public class CompoundPropertiesVerifier implements ElementPropertiesVerifier {
    private final Collection<ElementPropertiesVerifier> verifiers;

    public CompoundPropertiesVerifier(Collection<ElementPropertiesVerifier> verifiers) {
        this.verifiers = verifiers;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        return verifiers.stream().anyMatch(verifier -> verifier.applicableTo(element));
    }

    @Override
    public Collection<VerificationError> verify(ChainElement element) {
        return verifiers.stream().map(verifier -> verifier.verify(element))
                .flatMap(Collection::stream).collect(Collectors.toList());
    }
}
