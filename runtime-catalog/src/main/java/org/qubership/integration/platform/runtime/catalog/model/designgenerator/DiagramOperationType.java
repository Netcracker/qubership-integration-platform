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

package org.qubership.integration.platform.runtime.catalog.model.designgenerator;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Operation type for diagram operation")
public enum DiagramOperationType {

    // other
    DOCUMENT_START,
    DOCUMENT_END,
    AUTONUMBER,

    // participants
    ACTOR,
    ACTOR_AS,
    PARTICIPANT,
    PARTICIPANT_AS,

    // lines
    LINE_WITH_ARROW_SOLID_RIGHT,
    LINE_WITH_ARROW_DOTTED_RIGHT,
    LINE_WITH_OPEN_ARROW_SOLID_RIGHT,
    LINE_WITH_OPEN_ARROW_DOTTED_RIGHT,

    // activations
    ACTIVATE,
    DEACTIVATE,

    // common
    END,

    // loops
    START_LOOP,

    // alt
    START_ALT,
    ELSE,

    // opt
    START_OPT,

    // par
    START_PAR,
    PAR_ELSE,

    // areas
    START_COLORED_GROUP,
    START_GROUP,

    // other
    BLOCK_DELIMITER,
    DELAY,
}
