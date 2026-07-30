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

package org.qubership.integration.platform.runtime.catalog.model.exportimport.system;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;

import java.sql.Timestamp;

@Getter
@Setter
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonFilter("baseEntityFilter")
public class SpecificationSourceDto {
    private String id;
    private String name;
    private String description;
    private Timestamp createdWhen;
    private Timestamp modifiedWhen;
    private User createdBy;
    private User modifiedBy;

    // Only pre-api archives carry this key. It is neither exported nor applied on import: the hash is recomputed
    // from the source file the archive ships. Kept so the filter has something to suppress if a writer sets it again.
    private String sourceHash;

    @JsonProperty("filePath")
    @JsonAlias("fileName")
    private String fileName;

    @JsonProperty("isRoot")
    @JsonAlias("mainSource")
    private boolean mainSource;
}
