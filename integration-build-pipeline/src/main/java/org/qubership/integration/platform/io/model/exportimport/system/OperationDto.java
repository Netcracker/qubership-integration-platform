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

package org.qubership.integration.platform.io.model.exportimport.system;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;

/**
 * Serialization value of a system-model operation.
 *
 * <p>Shaped to match the persistence {@code Operation} entity field for field (same {@code id},
 * {@code name}, {@code description}, {@code method}, {@code path}, {@code specification},
 * {@code requestSchema}, and {@code responseSchemas} names), so an export deserializes into it
 * unchanged. It carries no JPA mapping; the catalog seam copies it to and from the persistence
 * entity at the model boundary. The persistence-only {@code chains} back-reference is not part of
 * the export format and is omitted here.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OperationDto implements Serializable {
    private String id;
    private String name;
    private String description;
    private String method;
    private String path;
    private JsonNode specification;
    private Map<String, JsonNode> requestSchema;
    private Map<String, JsonNode> responseSchemas;
}
