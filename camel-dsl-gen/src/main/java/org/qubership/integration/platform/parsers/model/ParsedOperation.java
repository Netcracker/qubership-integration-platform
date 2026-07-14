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

package org.qubership.integration.platform.parsers.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A single operation produced by a specification parser.
 *
 * <p>Carries only the fields a parser derives from a specification: the operation name, the method
 * and path, the operation specification, and the request and response schemas. Identity and version
 * belong to the persistence layer, so this model omits them; the catalog assigns an id when it maps
 * the operation onto its {@code Operation} entity.
 */
public interface ParsedOperation {

    String getName();

    void setName(String name);

    String getMethod();

    void setMethod(String method);

    String getPath();

    void setPath(String path);

    JsonNode getSpecification();

    void setSpecification(JsonNode specification);

    Map<String, JsonNode> getRequestSchema();

    void setRequestSchema(Map<String, JsonNode> requestSchema);

    Map<String, JsonNode> getResponseSchemas();

    void setResponseSchemas(Map<String, JsonNode> responseSchemas);
}
