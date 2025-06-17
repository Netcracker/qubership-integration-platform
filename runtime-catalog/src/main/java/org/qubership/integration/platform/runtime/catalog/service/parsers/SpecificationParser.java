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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
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
             SpecificationGroup group,
             Collection<SpecificationSource> sources,
             Set<String> oldSystemModelsIds,
             boolean isDiscovered,
             Consumer<String> messageHandler);

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
