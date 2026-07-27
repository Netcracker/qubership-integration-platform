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

package org.qubership.integration.platform.runtime.catalog.mapper;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MappingDescriptionValidator {

    private static final String NO_MAPPING_FOR_REQUIRED_ATTRIBUTES_ERROR_MESSAGE = "No mapping for structure with required attributes.";
    private static final String MANDATORY_FIELDS_MISSING_IN_MAPPING_ERROR_MESSAGE = "Mandatory fields are missing in current mapping.";


    public void validate(MappingDescription mappingDescription) {
        validateMandatoryFields(mappingDescription);
    }

    private void validateMandatoryFields(MappingDescription mappingDescription) {
        // Since changing a field's data type or its 'Required' flag during a schema update must not break the mapping,
        // we cannot validate that mandatory attributes are mapped anymore.
    }
}
