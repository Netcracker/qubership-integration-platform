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

@Schema(description = "Column name for a filter")
public enum FilterFeature {
    CHAIN_ID,
    CHAIN_NAME,
    ELEMENT_ID,
    ELEMENT_NAME,
    ELEMENT_TYPE,
    VALIDATION_SEVERITY,
    STATUS,
    ENGINES,
    LOGGING,
    NAME,
    ID,
    DESCRIPTION,
    BUSINESS_DESCRIPTION,
    ASSUMPTIONS,
    OUT_OF_SCOPE,
    PATH,
    METHOD,
    TOPIC,
    EXCHANGE,
    QUEUE,
    LABELS,
    ELEMENT,
    CREATED,
    SPECIFICATION_GROUP,
    SPECIFICATION_VERSION,
    URL,
    PROTOCOL,
    SERVICE_ID,
    ENTITY_TYPE,
    INSTRUCTION_ACTION,
    OVERRIDDEN_BY,
    MODIFIED_WHEN,
    CLASSIFIER
}
