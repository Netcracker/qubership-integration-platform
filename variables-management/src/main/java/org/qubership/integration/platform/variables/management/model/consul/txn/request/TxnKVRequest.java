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

package org.qubership.integration.platform.variables.management.model.consul.txn.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Base64;
import javax.annotation.Nullable;

@Getter
@Setter
@NoArgsConstructor
@Builder
public class TxnKVRequest {
    @JsonProperty("Verb")
    private TxnVerb verb;

    @JsonProperty("Key")
    private String key;

    @Nullable
    @JsonProperty("Value")
    private String value;

    public TxnKVRequest(TxnVerb verb, String key, @Nullable String value) {
        this.verb = verb;
        this.key = key;
        setValue(value);
    }

    public void setValue(@Nullable String value) {
        this.value = value != null ? Base64.getEncoder().encodeToString(value.getBytes()) : null;
    }
}
