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

import java.sql.Timestamp;

/**
 * A context service read from an import archive.
 *
 * <p>Carries the fields the catalog needs to rebuild its context-system entity: the identity and
 * description from {@link Entity}, plus the internal service name and the last-modification
 * timestamp exported alongside them.
 */
public interface ContextService extends Entity {

    /**
     * Name of the backing internal service, or {@code null} when the export does not record one.
     */
    String getInternalServiceName();

    /**
     * Last-modification timestamp preserved across an import round-trip, or {@code null} when the
     * export does not record one.
     */
    Timestamp getModifiedWhen();
}
