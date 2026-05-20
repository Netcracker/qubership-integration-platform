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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations;

import lombok.Getter;

@Getter
public class MigrationException extends Exception {
    private final String entityId;
    private final String entityName;

    public MigrationException(String message) {
        this(message, null, null);
    }

    public MigrationException(String message, String entityId, String entityName) {
        super(message);
        this.entityId = entityId;
        this.entityName = entityName;
    }

    public MigrationException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public MigrationException(String message, Throwable cause, String entityId, String entityName) {
        super(message, cause);
        this.entityId = entityId;
        this.entityName = entityName;
    }
}
