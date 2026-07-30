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

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;


public interface SpecificationParser {
     String SPECIFICATION_FILE_PROCESSING_ERROR = "An error occurred during parsing specification file";

     String ID_SEPARATOR = "-";

     SystemModel enrichSpecificationGroup(
             ApiGroup group,
             Collection<SpecificationSource> sources,
             Set<String> oldSystemModelsIds,
             boolean isDiscovered,
             boolean withSchemas,
             Consumer<String> messageHandler);

     /**
      * Picks the source a single-document parser reads. The persisted main-source flag is authoritative: import
      * guarantees exactly one (see {@code SpecificationImportService}, which promotes the first uploaded file when
      * none flags itself). The first-of-collection fallback only serves legacy rows carrying no flag, and is
      * deterministic because {@code SystemModel.specificationSources} is {@code @OrderBy("id")}; drop that ordering
      * and this read turns flaky.
      *
      * <p>Import and on-demand extraction both call this, so a multi-source model parses the same document either way.
      */
     static SpecificationSource mainSource(Collection<SpecificationSource> sources) {
          if (sources == null) {
               return null;
          }
          return sources.stream()
                  .filter(SpecificationSource::isMainSource)
                  .findFirst()
                  .orElseGet(() -> sources.stream().findFirst().orElse(null));
     }

     /** Content of {@link #mainSource}, or {@code null} when there is no source to read. */
     static String mainSourceText(Collection<SpecificationSource> sources) {
          SpecificationSource source = mainSource(sources);
          return source == null ? null : source.getSource();
     }

     default void checkSpecId(Set<String> oldSystemModelsIds, String systemModelId) throws SpecificationSimilarIdException {
          // skip spec if one already exists (by id) in a system
          if (oldSystemModelsIds.contains(systemModelId)) {
               throw new SpecificationSimilarIdException(systemModelId);
          }
     }

     default String buildId(String parentId, String entityName) {
          return parentId + ID_SEPARATOR + entityName;
     }

     default String buildOperationId(String systemModelId, String operationName) {
          String operationId = systemModelId + ID_SEPARATOR + operationName;
          return operationId.replaceAll("[\\[\\]]", "");
     }

     default void setOperationIds(
             String systemModelId,
             Collection<Operation> operations,
             Consumer<String> messageHandler
     ) {
          Set<String> ids = new HashSet<>();
          for (Operation operation : operations) {
               String idPrefix = buildOperationId(systemModelId, operation.getName());
               String id = idPrefix;
               int index = 0;
               while (ids.contains(id)) {
                    if (index == 0) {
                         String message = String.format("Duplicated operation identifier: %s. ", operation.getName());
                         messageHandler.accept(message);
                    }
                    ++index;
                    id = idPrefix + "-" + index;
               }
               operation.setId(id);
               ids.add(id);
          }
     }
}
