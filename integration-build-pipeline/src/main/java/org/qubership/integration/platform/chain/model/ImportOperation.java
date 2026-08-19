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

import java.util.Map;

/**
 * A system-model operation read from an import archive.
 *
 * <p>Carries exactly the fields the catalog reads to rebuild its {@code Operation} entity: the
 * identity and description from {@link Entity}, the HTTP method and path, the operation
 * specification, and the request and response schemas.
 */
public interface ImportOperation extends Entity {

    String getMethod();

    String getPath();

    JsonNode getSpecification();

    Map<String, JsonNode> getRequestSchema();

    Map<String, JsonNode> getResponseSchemas();
}
