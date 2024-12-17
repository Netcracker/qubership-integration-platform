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

package org.qubership.integration.platform.engine.opensearch.ism.deserializers;


import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.node.TextNode;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;

import java.io.IOException;

public class TimeValueDeserializer extends JsonDeserializer<TimeValue> {
    @Override
    public TimeValue deserialize(
            JsonParser jsonParser,
            DeserializationContext deserializationContext
    ) throws IOException, JacksonException {
        TreeNode node = jsonParser.readValueAsTree();
        if (node instanceof TextNode textNode) {
            return TimeValue.parseTimeValue(textNode.textValue(), jsonParser.getCurrentName());
        } else {
            String message = String.format("Wrong %s field type", jsonParser.getCurrentName());
            throw InvalidFormatException.from(jsonParser, message, node.asToken().asString(), TimeValue.class);
        }
    }
}
