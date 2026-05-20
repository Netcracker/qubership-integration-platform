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

package org.qubership.integration.platform.runtime.catalog.model.library;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Element type in terms of behavior")
public enum ElementType {
    @JsonProperty("module") MODULE,
    @JsonProperty("trigger") TRIGGER,

    /**
     * Trigger with input. In the middle of the chain,
     * it works like a normal element, but can be activated separately
     */
    @JsonProperty("composite-trigger") COMPOSITE_TRIGGER,
    @JsonProperty("system") SYSTEM,
    @JsonProperty("container") CONTAINER,
    @JsonProperty("swimlane") SWIMLANE,
    @JsonProperty("reuse") REUSE,
    @JsonProperty("reuse-reference") REUSE_REFERENCE
}
