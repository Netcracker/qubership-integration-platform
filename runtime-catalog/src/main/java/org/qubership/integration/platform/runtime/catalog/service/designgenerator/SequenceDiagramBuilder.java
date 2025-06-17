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

package org.qubership.integration.platform.runtime.catalog.service.designgenerator;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperation;
import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.*;


public class SequenceDiagramBuilder {

    private final Map<DiagramLangType, StringBuilder> sources = new HashMap<>();

    /**
     * Select all types
     */
    public SequenceDiagramBuilder() {
        this(DiagramLangType.values());
    }

    public SequenceDiagramBuilder(DiagramLangType... types) {
        for (DiagramLangType type : types) {
            sources.put(type, new StringBuilder());
        }
    }

    public SequenceDiagramBuilder append(DiagramOperationType operationType, String... args) {
        for (Map.Entry<DiagramLangType, StringBuilder> entry : sources.entrySet()) {
            entry.getValue().append(buildOperation(entry.getKey(), operationType, args));
        }
        return this;
    }

    public Map<DiagramLangType, String> build() {
        return sources.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, x -> x.getValue().toString()));
    }

    private String buildOperation(DiagramLangType langType, DiagramOperationType operationType, String... args) {
        DiagramOperation operation = OPERATIONS.get(langType).getOrDefault(operationType, EMPTY_OPERATION);
        String operationString = operation.getOperationString();

        args = operation.remapArguments(args);

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (operation.isEscapeArgument(i)) {
                arg = langType.escapeArgument(arg);
            }

            operationString = ARG_PLACEHOLDER_PATTERN
                    .matcher(operationString)
                    .replaceFirst(arg == null ? "" : arg.replace("$", "\\$"));
        }
        return operationString + (StringUtils.isEmpty(operationString) ? "\n" : langType.getLineTerminator());
    }
}
