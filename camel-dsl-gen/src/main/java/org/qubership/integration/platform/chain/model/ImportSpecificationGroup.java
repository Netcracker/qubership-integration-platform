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
 * A specification group read from an import archive.
 *
 * <p>Carries exactly the fields the catalog reads to rebuild its {@code SpecificationGroup} entity:
 * the identity and description from {@link Entity}, the audit users and timestamps, the source URL,
 * the synchronization flag, the labels, and the system models exported under the group. The
 * {@code parentId} points at the owning system so the catalog can attach the group to it.
 */
public interface ImportSpecificationGroup extends Entity {

    User getCreatedBy();

    Timestamp getCreatedWhen();

    User getModifiedBy();

    Timestamp getModifiedWhen();

    String getUrl();

    boolean isSynchronization();

    String getParentId();

    List<String> getLabels();

    List<ImportSystemModel> getSystemModels();
}
