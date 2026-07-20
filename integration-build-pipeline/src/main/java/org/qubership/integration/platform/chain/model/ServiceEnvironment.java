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

package org.qubership.integration.platform.chain.model;

import java.util.Map;

public interface ServiceEnvironment extends Entity {
    String getSystemId();

    String getAddress();

    EnvironmentSourceType getSourceType();

    Map<String, Object> getProperties();

    boolean isActivated();

    /**
     * Creation timestamp (epoch milliseconds) preserved across an import round-trip. Adapters that
     * do not track it return {@code null}.
     */
    default Long getCreatedWhen() {
        return null;
    }

    /**
     * Last-modification timestamp (epoch milliseconds) preserved across an import round-trip. Adapters
     * that do not track it return {@code null}.
     */
    default Long getModifiedWhen() {
        return null;
    }
}
