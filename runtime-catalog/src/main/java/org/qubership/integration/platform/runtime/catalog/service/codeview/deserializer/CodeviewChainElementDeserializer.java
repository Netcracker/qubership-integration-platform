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

package org.qubership.integration.platform.runtime.catalog.service.codeview.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class CodeviewChainElementDeserializer extends StdDeserializer<ChainElement> {

    private final ObjectMapper objectMapper;

    public CodeviewChainElementDeserializer(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        super(ChainElement.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public ChainElement deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ChainElement resultElement = new ChainElement();
        YAMLMapper mapper = (YAMLMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);

        JsonNode idNode = node.get("id");
        JsonNode nameNode = node.get("name");
        JsonNode descriptionNode = node.get("description");

        ObjectNode propertiesNode = (ObjectNode) node.get("properties");

        resultElement.setId(idNode != null && !(idNode instanceof NullNode) ? idNode.asText() : null);
        resultElement.setName(nameNode != null && !(nameNode instanceof NullNode) ? nameNode.asText() : null);
        resultElement.setDescription(descriptionNode != null && !(descriptionNode instanceof NullNode) ? descriptionNode.asText() : null);

        restoreProperties(propertiesNode, resultElement);

        return resultElement;
    }

    private void restoreProperties(ObjectNode propertiesNode, ChainElement resultElement) {
        Map<String, Object> elementProperties = new HashMap<>();
        if (propertiesNode != null) {
            elementProperties = objectMapper.convertValue(propertiesNode, new TypeReference<>(){});
        }

        resultElement.getProperties().putAll(elementProperties);
    }
}
