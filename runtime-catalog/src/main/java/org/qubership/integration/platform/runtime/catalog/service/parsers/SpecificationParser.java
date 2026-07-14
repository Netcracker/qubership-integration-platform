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

package org.qubership.integration.platform.runtime.catalog.service.parsers;

import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;

import java.util.Collection;
import java.util.function.Consumer;


public interface SpecificationParser {
     String SPECIFICATION_FILE_PROCESSING_ERROR = "An error occurred during parsing specification file";

     String ID_SEPARATOR = "-";

     /**
      * Parses the sources into a system model. The result carries the parsed operations, the
      * description, and the version the specification declares, if any. The parser does not touch
      * persistence: it assigns no identity or version name and saves nothing. The caller resolves
      * the version name, assigns ids, and persists the model.
      *
      * <p>A parser may still apply its existing environment side effects on the owning system.
      *
      * @param group the specification group the model will belong to
      * @param sources the specification sources to parse
      * @param messageHandler receives human-readable warnings raised while parsing
      * @return the parsed system model
      */
     ParsedSystemModel parseSpecification(
             SpecificationGroup group,
             Collection<SpecificationSource> sources,
             Consumer<String> messageHandler);
}
