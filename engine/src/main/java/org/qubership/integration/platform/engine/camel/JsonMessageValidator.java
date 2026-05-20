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

package org.qubership.integration.platform.engine.camel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JsonMessageValidator {
    public static final String MESSAGE_VALIDATION_ERROR = "Errors during message validation: ";
    private static final String PARSE_MESSAGE_BODY_ERROR = "Unable to parse message body";
    private static final String EMPTY_BODY_ERROR = "Message body is empty";

    private final ObjectMapper objectMapper;

    @Autowired
    public JsonMessageValidator(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(String jsonMessageAsString, String jsonSchemaAsString) {
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            JsonSchema schemaNode = factory.getSchema(jsonSchemaAsString);

            if (StringUtils.isBlank(jsonMessageAsString)) {
                throw new ValidationException(EMPTY_BODY_ERROR);
            }

            JsonNode messageNode = objectMapper.readTree(jsonMessageAsString);
            Set<ValidationMessage> errors = schemaNode.validate(messageNode);
            if (!errors.isEmpty()) {
                String validationMessages = errors
                        .stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining(", "));
                throw new ValidationException(MESSAGE_VALIDATION_ERROR.concat(validationMessages));
            }
        } catch (JsonProcessingException e) {
            throw new ValidationException(PARSE_MESSAGE_BODY_ERROR);
        }
    }
}
