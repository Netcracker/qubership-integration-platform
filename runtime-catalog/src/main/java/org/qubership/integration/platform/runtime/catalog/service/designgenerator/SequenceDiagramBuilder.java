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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.*;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;


public class SequenceDiagramBuilder {

    /**
     * Operations that put an interaction on the diagram. Everything else only frames
     * interactions, so a block holding none of these renders as an empty box.
     */
    private static final Set<DiagramOperationType> CONTENT_OPERATIONS = EnumSet.of(
            LINE_WITH_ARROW_SOLID_RIGHT,
            LINE_WITH_ARROW_DOTTED_RIGHT,
            LINE_WITH_OPEN_ARROW_SOLID_RIGHT,
            LINE_WITH_OPEN_ARROW_DOTTED_RIGHT);

    private final Map<DiagramLangType, StringBuilder> sources = new HashMap<>();

    private int contentOperationCount = 0;

    /**
     * A position in every source under construction. Taking one lets a block be
     * dropped after the fact, once its content turns out to be empty.
     *
     * @param lengths source length per diagram language
     * @param contentOperationCount interactions written so far
     */
    public record Checkpoint(Map<DiagramLangType, Integer> lengths, int contentOperationCount) {
    }

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
        if (CONTENT_OPERATIONS.contains(operationType)) {
            contentOperationCount++;
        }
        return this;
    }

    public Checkpoint checkpoint() {
        Map<DiagramLangType, Integer> lengths = sources.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().length()));
        return new Checkpoint(lengths, contentOperationCount);
    }

    public boolean hasContentSince(Checkpoint checkpoint) {
        return contentOperationCount > checkpoint.contentOperationCount();
    }

    /**
     * Discards everything appended after the checkpoint.
     */
    public SequenceDiagramBuilder revertTo(Checkpoint checkpoint) {
        checkpoint.lengths().forEach((langType, length) -> sources.get(langType).setLength(length));
        contentOperationCount = checkpoint.contentOperationCount();
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
                    .replaceFirst(arg == null ? "" : java.util.regex.Matcher.quoteReplacement(arg));
        }
        return operationString + (StringUtils.isEmpty(operationString) ? "\n" : langType.getLineTerminator());
    }
}
