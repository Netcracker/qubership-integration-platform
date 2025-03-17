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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.qubership.integration.platform.catalog.exception.SnapshotCreationException;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.mapper.MappingDescriptionValidator;
import org.qubership.integration.platform.runtime.catalog.mapper.MappingInterpreter;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Optional;

import static java.util.Objects.isNull;

@TemplatesHelper("mapper-interpretation")
public class MapperInterpretatorHelper extends BaseHelper implements Helper<String> {

    private final ObjectMapper objectMapper;
    private final MappingInterpreter interpreter;
    private final MappingDescriptionValidator validator;

    @Autowired
    public MapperInterpretatorHelper(
            MappingInterpreter interpreter,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            MappingDescriptionValidator validator
    ) {
        this.interpreter = interpreter;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }


    @Override
    public String apply(String mappingDescriptionAsString, Options options) {
        try {
            MappingDescription mappingDescription = isNull(mappingDescriptionAsString)
                    ? new MappingDescription(null, null, null, null, null)
                    : objectMapper.readValue(mappingDescriptionAsString, MappingDescription.class);
            validator.validate(mappingDescription);
            return interpreter.getInterpretation(mappingDescription);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error processing json in property-json helper", e);
        } catch (SnapshotCreationException e) {
            var context = options.context.model();
            if (context instanceof ChainElement element && isNull(e.getElementId())) {
                e.setElementId(Optional.ofNullable(element.getOriginalId()).orElse(element.getId()));
                e.setElementName(element.getName());
            }
            throw e;
        }
    }
}
