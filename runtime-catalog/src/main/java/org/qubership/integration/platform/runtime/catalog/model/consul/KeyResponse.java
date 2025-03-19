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

package org.qubership.integration.platform.runtime.catalog.model.consul;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeyResponse {
    @JsonProperty("LockIndex")
    private long lockIndex;

    @JsonProperty("Key")
    private String key;

    @JsonProperty("Flags")
    private long flags;

    @JsonProperty("Value")
    private String value;

    @JsonProperty("CreateIndex")
    private long createIndex;

    @JsonProperty("ModifyIndex")
    private long modifyIndex;

    public String getDecodedValue() {
        return this.value == null
                ? null
                : new String(Base64.getDecoder().decode(this.value), StandardCharsets.UTF_8);
    }
}
