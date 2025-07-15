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

package org.qubership.integration.platform.variables.management.persistence.configs.entity.enums.filter;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Audit log filter condition")
public enum FilterCondition {
    IS,
    IS_NOT,
    CONTAINS,
    DOES_NOT_CONTAIN,
    STARTS_WITH,
    ENDS_WITH,
    EMPTY,
    NOT_EMPTY,
    IN,
    NOT_IN,
    IS_WITHIN,
    IS_AFTER,
    IS_BEFORE
}
