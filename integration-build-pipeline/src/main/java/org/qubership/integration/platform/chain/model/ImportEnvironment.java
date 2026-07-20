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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * An integration-system environment read from an import archive.
 *
 * <p>Carries exactly the fields the catalog reads to rebuild its {@code Environment} entity: the
 * identity and description from {@link Entity}, the address, the source type, the label names, the
 * MaaS instance id, and the properties tree. The label names become persistence
 * {@code EnvironmentLabel} values at the catalog seam.
 */
public interface ImportEnvironment extends Entity {

    String getAddress();

    EnvironmentSourceType getSourceType();

    List<String> getLabels();

    @Deprecated
    String getMaasInstanceId();

    JsonNode getProperties();
}
