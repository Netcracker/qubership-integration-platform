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
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;

import java.io.Serializable;
import java.util.List;

/**
 * Serialization value of an integration-system environment.
 *
 * <p>Shaped to match the persistence {@code Environment} entity field for field (same {@code id},
 * {@code name}, {@code description}, {@code address}, {@code sourceType}, {@code labels},
 * {@code maasInstanceId}, and {@code properties} names), so an export deserializes into it
 * unchanged. It carries no JPA mapping; the catalog seam copies it to and from the persistence
 * entity at the model boundary. The label names stay as strings here and become persistence
 * {@code EnvironmentLabel} values at the seam.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentDto implements Serializable {
    private String id;
    private String name;
    private String description;
    private String address;

    @Builder.Default
    private EnvironmentSourceType sourceType = EnvironmentSourceType.MANUAL;

    private List<String> labels;

    @Deprecated
    private String maasInstanceId;

    private JsonNode properties;
}
