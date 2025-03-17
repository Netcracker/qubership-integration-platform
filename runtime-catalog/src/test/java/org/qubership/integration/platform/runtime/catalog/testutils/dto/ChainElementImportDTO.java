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

package org.qubership.integration.platform.runtime.catalog.testutils.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@SuperBuilder
public class ChainElementImportDTO extends ImportEntityDTO {

    @JsonProperty("element-type")
    private final String type;
    @JsonProperty("swimlane-id")
    private final String swimlaneId;
    private final List<ChainElementImportDTO> children;
    private final Map<String, Object> properties;
    @JsonProperty("original-id")
    private final String originalId;
    @JsonProperty("service-environment")
    private final ServiceEnvironment serviceEnvironment;
    @JsonProperty("properties-filename")
    private final String propertiesFilename;

    public String getType() {
        return type;
    }

    public String getSwimlaneId() {
        return swimlaneId;
    }

    public List<ChainElementImportDTO> getChildren() {
        return children != null ? children : new ArrayList<>();
    }

    public Map<String, Object> getProperties() {
        return properties != null ? properties : new HashMap<>();
    }

    public String getOriginalId() {
        return originalId;
    }

    public ServiceEnvironment getServiceEnvironment() {
        return serviceEnvironment;
    }

    public String getPropertiesFilename() {
        return propertiesFilename;
    }
}
