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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.event;


import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class GenericMessage {
    private String message;
    private GenericMessageType type;

    @Builder.Default
    private Map<String, String> optionalFields = new HashMap<>();


    public void setOptionalFields(Map<String, String> optionalFields) {
        this.optionalFields = optionalFields == null ? new HashMap<>() : optionalFields;
    }

    @SuppressWarnings("unused")
    public static class GenericMessageBuilder {
        public GenericMessageBuilder optionalFields(final Map<String, String> optionalFields) {
            if (optionalFields != null) {
                this.optionalFields$value = optionalFields;
                this.optionalFields$set = true;
            }
            return this;
        }
    }
}
