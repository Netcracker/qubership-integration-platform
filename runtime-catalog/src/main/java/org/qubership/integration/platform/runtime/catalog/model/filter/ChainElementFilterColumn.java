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

@Schema(description = "Column on which filter will be applied")
public enum ChainElementFilterColumn {
    ENDPOINT(IS, IS_NOT, CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH, EMPTY),
    TYPE(IN, NOT_IN),
    ROLES(IS, IS_NOT, CONTAINS, DOES_NOT_CONTAIN, EMPTY, NOT_EMPTY),
    CHAIN(CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH),
    CHAIN_STATUS(IS, IS_NOT, IN, NOT_IN),
    ROLES_RESOURCE(IS, IS_NOT, CONTAINS, DOES_NOT_CONTAIN, EMPTY, NOT_EMPTY),
    ACCESS_CONTROL_TYPE(IS, IS_NOT);

    // Conditions the query builders translate; anything else is rejected before the query runs.
    // EnumSet iterates in declaration order, which is the order the error message reports.
    @Getter
    private final Set<FilterCondition> supportedConditions;

    ChainElementFilterColumn(FilterCondition... supportedConditions) {
        this.supportedConditions = Collections.unmodifiableSet(EnumSet.copyOf(List.of(supportedConditions)));
    }
}
