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

import com.networknt.schema.*;
import com.networknt.schema.Error;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.ValidationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class JsonMessageValidator {
    public static final String MESSAGE_VALIDATION_ERROR = "Errors during message validation: ";
    public static final String EMPTY_BODY_ERROR = "Message body is empty";

    public void validate(String jsonMessageAsString, String jsonSchemaAsString) {
        SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
        Schema schema = schemaRegistry.getSchema(jsonSchemaAsString);

        if (StringUtils.isBlank(jsonMessageAsString)) {
            throw new ValidationException(EMPTY_BODY_ERROR);
        }

        List<Error> errors = schema.validate(jsonMessageAsString, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String validationMessages = errors
                    .stream()
                    .map(Error::getMessage)
                    .collect(Collectors.joining(", "));
            throw new ValidationException(MESSAGE_VALIDATION_ERROR.concat(validationMessages));
        }
    }
}
