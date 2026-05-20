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

package org.qubership.integration.platform.runtime.catalog.exception.exceptions;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.Collection;
import java.util.Optional;

@Getter
public class WrongChainElementTypeException extends ChainElementVerificationException {
    private final String elementType;
    private final Collection<String> expectedTypes;

    public WrongChainElementTypeException(ChainElement element, Collection<String> expectedTypes) {
        super(element, buildMessage(element, expectedTypes));
        this.elementType = extractElementType(element);
        this.expectedTypes = expectedTypes;
    }

    private static String buildMessage(ChainElement element, Collection<String> expectedTypes) {
        return String.format("Wrong element type. Got: %s, expected: %s.",
                extractElementType(element), String.join(", ", expectedTypes));
    }

    private static String extractElementType(ChainElement element) {
        return Optional.ofNullable(element).map(ChainElement::getType).orElse("-");
    }

}
