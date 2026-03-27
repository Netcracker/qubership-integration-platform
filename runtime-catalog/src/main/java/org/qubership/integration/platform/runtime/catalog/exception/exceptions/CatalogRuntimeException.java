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

@Getter
@Setter
public class CatalogRuntimeException extends RuntimeException {

    private Exception originalException;

    public CatalogRuntimeException() {
        super();
    }

    public CatalogRuntimeException(String errorMessage) {
        super(errorMessage);
    }

    public CatalogRuntimeException(String errorMessage, Throwable cause) {
        super(errorMessage, cause);
    }

    public CatalogRuntimeException(String errorMessage, Exception originalException) {
        super(errorMessage);
        this.originalException = originalException;
    }
}
