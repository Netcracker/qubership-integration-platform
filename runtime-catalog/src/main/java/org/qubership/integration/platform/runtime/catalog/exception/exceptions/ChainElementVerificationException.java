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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.Optional;

@Getter
public class ChainElementVerificationException extends ApiSpecificationExportException {
    private final String chainId;
    private final String elementId;

    public ChainElementVerificationException(ChainElement element, String message) {
        super(buildMessage(element, message));
        this.chainId = extractChainId(element);
        this.elementId = extractElementId(element);
    }

    private static String buildMessage(ChainElement element, String message) {
        return String.format("Chain ID: %s, element ID: %s. %s",
                extractChainId(element), extractElementId(element), message);
    }

    protected static String extractChainId(ChainElement element) {
        return Optional.ofNullable(element).map(ChainElement::getChain).map(Chain::getId).orElse(null);
    }

    protected static String extractElementId(ChainElement element) {
        return Optional.ofNullable(element).map(ChainElement::getId).orElse(null);
    }

}
