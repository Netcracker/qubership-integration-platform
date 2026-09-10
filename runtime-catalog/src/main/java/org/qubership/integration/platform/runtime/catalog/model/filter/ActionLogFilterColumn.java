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

package org.qubership.integration.platform.runtime.catalog.model.filter;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition.*;

@Schema(description = "Audit log column name for filter")
public enum ActionLogFilterColumn {
    OPERATION(TextConditions.ALL),
    ENTITY_ID(TextConditions.ALL),
    ENTITY_TYPE(TextConditions.ALL),
    ENTITY_NAME(TextConditions.ALL),
    PARENT_ID(TextConditions.ALL),
    PARENT_NAME(TextConditions.ALL),
    REQUEST_ID(TextConditions.ALL),
    ACTION_TIME(IS_AFTER, IS_BEFORE, IS_WITHIN),
    INITIATOR(TextConditions.ALL);

    // Conditions the query builder translates for this column. The rest used to answer 500 on a type
    // mismatch, or 200 with the whole table where the builder had no case for them.
    @Getter
    private final Set<FilterCondition> supportedConditions;

    ActionLogFilterColumn(FilterCondition... supportedConditions) {
        this.supportedConditions = Collections.unmodifiableSet(EnumSet.copyOf(List.of(supportedConditions)));
    }

    private static final class TextConditions {
        private static final FilterCondition[] ALL = {
                IS, IS_NOT, CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH, IN, NOT_IN, EMPTY, NOT_EMPTY
        };

        private TextConditions() {
        }
    }
}
