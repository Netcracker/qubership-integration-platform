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
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.Optional;

import static java.util.Objects.isNull;

@Setter
@Getter
public class SnapshotCreationException extends CatalogRuntimeException {
    private String chainId;
    private String elementId;
    private String elementName;


    public SnapshotCreationException(String message) {
        this(message, null);
    }

    public SnapshotCreationException(String message, ChainElement element) {
        this(message, element, null);
    }

    public SnapshotCreationException(String message, ChainElement element, Exception exception) {
        super(message, exception);
        this.elementId = isNull(element) ? "" : extractElementId(element);
        this.elementName = isNull(element) ? "" : element.getName();
        this.chainId = isNull(element) || isNull(element.getChain()) ? "" : element.getChain().getName();
    }

    public SnapshotCreationException(String message, String chainId, ChainElement element, Exception exception) {
        super(message, exception);
        this.elementId = isNull(element) ? "" : extractElementId(element);
        this.elementName = isNull(element) ? "" : element.getName();
        this.chainId = isNull(chainId) ? "" : chainId;
    }

    private static String extractElementId(ChainElement element) {
        return Optional.ofNullable(element.getOriginalId()).orElse(element.getId());
    }

    public String toString() {
        return isNull(elementId)
                ? super.toString()
                : String.format("%s Element id: %s. Element name: %s.", super.toString(), elementId, elementName);
    }
}
