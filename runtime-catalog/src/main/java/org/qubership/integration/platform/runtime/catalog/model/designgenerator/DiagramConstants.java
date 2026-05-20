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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType.MERMAID;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramLangType.PLANT_UML;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;

public class DiagramConstants {

    public static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("##\\{[^}]+}");
    public static final String EMPTY_PROPERTY_STUB = "%empty_property%";
    public static final String DEFAULT_RESPONSE_TITLE = "Response";

    public static final String[] GROUP_BG_RGB = {"250", "250", "250"}; // group color

    public static final Map<String, ReservedPlaceholders> RESERVED_PLACEHOLDERS = Arrays.stream(ReservedPlaceholders.values())
            .collect(Collectors.toMap(ReservedPlaceholders::getPlaceholder, Function.identity()));


    public static final String ARG_PLACEHOLDER = "<ARG_PLACEHOLDER>";
    public static final Pattern ARG_PLACEHOLDER_PATTERN = Pattern.compile(ARG_PLACEHOLDER);

    // <language_type, <operation, operation>>
    public static final Map<DiagramLangType, Map<DiagramOperationType, DiagramOperation>> OPERATIONS = fillOperations();

    public static final DiagramOperation EMPTY_OPERATION = new DiagramOperation("");


    private DiagramConstants() {
    }

    private static Map<DiagramLangType, Map<DiagramOperationType, DiagramOperation>> fillOperations() {
        Map<DiagramLangType, Map<DiagramOperationType, DiagramOperation>> operations = new HashMap<>();
        fillMermaidOperations(operations);
        fillPlantUMLOperations(operations);
        return operations;
    }

    private static void fillMermaidOperations(Map<DiagramLangType, Map<DiagramOperationType, DiagramOperation>> operations) {
        Map<DiagramOperationType, DiagramOperation> operationsMap = new HashMap<>();

        operationsMap.put(DOCUMENT_START, createOperation("sequenceDiagram"));
        operationsMap.put(DOCUMENT_END, createOperation(""));

        operationsMap.put(PAR_ELSE, createOperation("and " + ARG_PLACEHOLDER));
        operationsMap.put(PARTICIPANT_AS, createOperation("participant " + ARG_PLACEHOLDER + " as " + ARG_PLACEHOLDER));

        operationsMap.put(START_COLORED_GROUP,
                createOperation(
                        "rect rgb(" + ARG_PLACEHOLDER + ", " + ARG_PLACEHOLDER + ", " + ARG_PLACEHOLDER + ");\n"
                                + "note right of " + ARG_PLACEHOLDER + " : " + ARG_PLACEHOLDER
                ));

        buildLines(operationsMap, "->>", "-->>", "-)", "--)");
        fillCommonOperations(operationsMap);

        operations.put(MERMAID, operationsMap);
    }

    private static void fillPlantUMLOperations(Map<DiagramLangType, Map<DiagramOperationType, DiagramOperation>> operations) {
        Map<DiagramOperationType, DiagramOperation> operationsMap = new HashMap<>();

        operationsMap.put(DOCUMENT_START, createOperation("@startuml"));
        operationsMap.put(DOCUMENT_END, createOperation("@enduml"));

        operationsMap.put(START_GROUP, createOperation("group " + ARG_PLACEHOLDER));
        operationsMap.put(PAR_ELSE, createOperation("else " + ARG_PLACEHOLDER));
        operationsMap.put(PARTICIPANT_AS, createOperation(
                "participant " + ARG_PLACEHOLDER + " as " + ARG_PLACEHOLDER,
                new ArgumentParameters(false, 0, 1),
                new ArgumentParameters(true, 1, 0)
        ));
        operationsMap.put(DELAY, createOperation("..."));

        buildLines(operationsMap, "->", "-->", "->>", "-->>");
        fillCommonOperations(operationsMap);

        operations.put(PLANT_UML, operationsMap);
    }

    private static void fillCommonOperations(Map<DiagramOperationType, DiagramOperation> operationsMap) {
        operationsMap.put(AUTONUMBER, createOperation("autonumber"));
        operationsMap.put(BLOCK_DELIMITER, createOperation(""));

        operationsMap.put(ACTOR, createOperation("actor " + ARG_PLACEHOLDER));
        operationsMap.put(ACTOR_AS, createOperation("actor " + ARG_PLACEHOLDER + " as " + ARG_PLACEHOLDER));

        operationsMap.put(PARTICIPANT, createOperation("participant " + ARG_PLACEHOLDER));

        operationsMap.put(ACTIVATE, createOperation("activate " + ARG_PLACEHOLDER));
        operationsMap.put(DEACTIVATE, createOperation("deactivate " + ARG_PLACEHOLDER));

        operationsMap.put(END, createOperation("end"));

        operationsMap.put(START_LOOP, createOperation("loop " + ARG_PLACEHOLDER));
        operationsMap.put(START_ALT, createOperation("alt " + ARG_PLACEHOLDER));
        operationsMap.put(ELSE, createOperation("else " + ARG_PLACEHOLDER));
        operationsMap.put(START_OPT, createOperation("opt " + ARG_PLACEHOLDER));
        operationsMap.put(START_PAR, createOperation("par " + ARG_PLACEHOLDER));
    }

    private static void buildLines(Map<DiagramOperationType, DiagramOperation> operationsMap, String ...arrows) {
        operationsMap.put(LINE_WITH_ARROW_SOLID_RIGHT, buildLineTemplate(arrows[0]));
        operationsMap.put(LINE_WITH_ARROW_DOTTED_RIGHT, buildLineTemplate(arrows[1]));
        operationsMap.put(LINE_WITH_OPEN_ARROW_SOLID_RIGHT, buildLineTemplate(arrows[2]));
        operationsMap.put(LINE_WITH_OPEN_ARROW_DOTTED_RIGHT, buildLineTemplate(arrows[3]));
    }

    private static DiagramOperation buildLineTemplate(String arrow) {
        return createOperation(ARG_PLACEHOLDER + " " + arrow + " " + ARG_PLACEHOLDER + " : " + ARG_PLACEHOLDER);
    }

    private static DiagramOperation createOperation(String operationString) {
        return new DiagramOperation(operationString);
    }

    /**
     * You can use parameters to change the order of input arguments or specify additional parameters
     */
    private static DiagramOperation createOperation(String operationString, ArgumentParameters...params) {
        return new DiagramOperation(operationString, params);
    }
}
