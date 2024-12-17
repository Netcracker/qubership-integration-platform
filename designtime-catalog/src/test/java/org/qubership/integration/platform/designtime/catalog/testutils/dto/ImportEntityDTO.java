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

package org.qubership.integration.platform.designtime.catalog.testutils.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.sql.Timestamp;

@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@SuperBuilder
public class ImportEntityDTO {

    private final String id;
    private final String name;
    private final String description;
    private final Timestamp modifiedWhen;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Timestamp getModifiedWhen() {
        return modifiedWhen;
    }
}
