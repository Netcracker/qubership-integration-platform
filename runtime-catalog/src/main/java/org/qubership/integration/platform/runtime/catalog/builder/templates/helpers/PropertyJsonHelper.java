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
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;

/**
 * Handlebars helper, that receives property with required name and
 * returns it in json format
 */
@TemplatesHelper("property-json")
public class PropertyJsonHelper extends BaseHelper implements Helper<String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(); // todo: replace with spring bean

    @Override
    public String apply(String propertyName, Options options) {
        Object value = getPropertyValue(propertyName, options);
        try {
            return value != null ? OBJECT_MAPPER.writeValueAsString(value) : null;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error processing json in property-json helper", e);
        }
    }
}
