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

package org.qubership.integration.platform.parsers;

import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Parses specification sources into a system model, free of any persistence dependency.
 *
 * <p>The result carries the parsed operations, the description, and the version the specification
 * declares, if any. The parser assigns no identity or version name and saves nothing: the caller
 * resolves the version name, assigns ids, and persists the model.
 */
public interface SpecificationParser {

    String SPECIFICATION_FILE_PROCESSING_ERROR = "An error occurred during parsing specification file";

    String ID_SEPARATOR = "-";

    /**
     * Parses the sources into a system model.
     *
     * @param groupId the id of the specification group the model will belong to
     * @param sources the specification sources to parse
     * @param messageHandler receives human-readable warnings raised while parsing
     * @return the parsed system model
     * @throws SpecificationParserException if the sources cannot be parsed
     */
    ParsedSystemModel parseSpecification(
            String groupId,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler);
}
