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

package org.qubership.integration.platform.engine.service;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Status of the element or session")
public enum ExecutionStatus {
    IN_PROGRESS,
    COMPLETED_NORMALLY,
    COMPLETED_WITH_WARNINGS,
    COMPLETED_WITH_ERRORS,
    CANCELLED_OR_UNKNOWN;

    public static ExecutionStatus computeHigherPriorityStatus(ExecutionStatus firstStatus, ExecutionStatus secondStatus) {
        if (IN_PROGRESS.equals(firstStatus) || IN_PROGRESS.equals(secondStatus)) {
            return IN_PROGRESS;
        }
        if (COMPLETED_WITH_ERRORS.equals(firstStatus) || COMPLETED_WITH_ERRORS.equals(secondStatus)) {
            return COMPLETED_WITH_ERRORS;
        }
        if (COMPLETED_WITH_WARNINGS.equals(firstStatus) || COMPLETED_WITH_WARNINGS.equals(secondStatus)) {
            return COMPLETED_WITH_WARNINGS;
        }
        return COMPLETED_NORMALLY;
    }

    public static String formatToLogStatus(ExecutionStatus status) {
        if (status == null) {
            return "";
        }

        String[] words = status.name().split("_");

        StringBuilder formattedString = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                formattedString.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(" ");
            }
        }

        return formattedString.toString().trim();
    }
}
