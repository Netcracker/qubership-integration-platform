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

import org.qubership.integration.platform.io.model.exportimport.system.User;

import java.sql.Timestamp;
import java.util.List;

/**
 * An MCP service read from an import archive.
 *
 * <p>Carries the fields the catalog needs to rebuild its MCP system entity: the identity and
 * description from {@link Entity}, the MCP {@code identifier} and {@code instructions}, the audit
 * user and timestamps recorded on create and modify, and the label names exported alongside them.
 */
public interface McpService extends Entity {

    /**
     * MCP identifier of the service, or {@code null} when the export does not record one.
     */
    String getIdentifier();

    /**
     * Instructions supplied to the MCP client, or {@code null} when the export does not record any.
     */
    String getInstructions();

    /**
     * User who created the service, or {@code null} when the export does not record one.
     */
    User getCreatedBy();

    /**
     * Creation timestamp preserved across an import round-trip, or {@code null} when absent.
     */
    Timestamp getCreatedWhen();

    /**
     * User who last modified the service, or {@code null} when the export does not record one.
     */
    User getModifiedBy();

    /**
     * Last-modification timestamp preserved across an import round-trip, or {@code null} when absent.
     */
    Timestamp getModifiedWhen();

    /**
     * Label names attached to the service. Never {@code null}; empty when the export records none.
     */
    List<String> getLabels();
}
