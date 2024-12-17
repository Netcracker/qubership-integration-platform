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

package org.qubership.integration.platform.engine.service.debugger.masking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.qubership.integration.platform.engine.errorhandling.LoggingMaskingException;
import org.qubership.integration.platform.engine.model.SessionElementProperty;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Slf4j
@Component
public class MaskingService {
    private static final MimeType SOAP_XML_CONTENT_TYPE = MimeType.valueOf("application/soap+xml");
    private static final MimeType JSON_PATCH_JSON_CONTENT_TYPE = MimeType.valueOf("application/json-patch+json");
    private static final MimeType X_WWW_FORM_URLENCODED_CONTENT_TYPE = MimeType.valueOf("application/x-www-form-urlencoded");

    private final ObjectMapper objectMapper;

    @Autowired
    public MaskingService(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String maskFields(String target, Set<String> fields, MimeType contentType)
        throws LoggingMaskingException, UnsupportedMediaTypeException {
        if (contentType == null) {
            throw new UnsupportedMediaTypeException(
                "Content type is empty, failed to mask fields in payload");
        }

        if (MimeTypeUtils.APPLICATION_JSON.equalsTypeAndSubtype(contentType) ||
            JSON_PATCH_JSON_CONTENT_TYPE.equalsTypeAndSubtype(contentType)
        ) {
            try {
                return maskJSON(target, fields);
            } catch (JsonProcessingException e) {
                throw new LoggingMaskingException(
                    "Invalid JSON document, failed to mask fields in payload", e);
            }
        }

        if (MimeTypeUtils.APPLICATION_XML.equalsTypeAndSubtype(contentType) ||
            MimeTypeUtils.TEXT_XML.equalsTypeAndSubtype(contentType) ||
            SOAP_XML_CONTENT_TYPE.equalsTypeAndSubtype(contentType)
        ) {
            try {
                return maskXML(target, fields);
            } catch (Exception e) {
                throw new LoggingMaskingException(
                    "Invalid XML document, failed to mask fields in payload", e);
            }
        }

        if (X_WWW_FORM_URLENCODED_CONTENT_TYPE.equalsTypeAndSubtype(contentType)) {
            try {
                return maskXwwwUrlencoded(target, fields);
            }  catch (Exception e) {
                throw new LoggingMaskingException(
                    "Invalid x-www-form-urlencoded data, failed to mask fields in payload", e);
            }
        }

        throw new UnsupportedMediaTypeException(
            "Content type " + contentType.toString() + " not supported for masking");
    }

    public void maskPropertiesFields(Map<String, SessionElementProperty> target, Set<String> maskedFields) {
        for (String field : maskedFields) {
            target.computeIfPresent(field, (key, value) -> {
                value.setValue(CamelConstants.MASKING_TEMPLATE);
                return value;
            });
        }
    }

    public void maskFields(Map<String, String> target, Set<String> maskedFields) {
        for (String field : maskedFields) {
            target.computeIfPresent(field, (key, value) -> CamelConstants.MASKING_TEMPLATE);
        }
    }

    public String maskJSON(String target, Set<String> fields) throws JsonProcessingException {
        JsonNode jsonNode = objectMapper.readTree(target);
        modifyJsonTree(jsonNode, fields);
        return objectMapper.writeValueAsString(jsonNode);
    }

    private String maskXML(String target, Set<String> fields) throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setValidating(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        Document document = documentBuilder.parse(new InputSource(new StringReader(target)));
        document.getDocumentElement().normalize();
        Node root = document.getDocumentElement();

        modifyXmlTree(root, fields);

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.getBuffer().toString();
    }

    private String maskXwwwUrlencoded(String target, Set<String> fields) {
        // parse payload
        List<Pair<String, String>> maskedEntries = Arrays.stream(target.split("&"))
            .map(kv -> {
                String[] split = kv.split("=");
                String decodedKey = URLDecoder.decode(split[0], StandardCharsets.UTF_8);
                String value = split.length > 1 ? split[1] : "";
                return Pair.of(split[0], fields.contains(decodedKey) ? CamelConstants.MASKING_TEMPLATE : value);
            })
            .toList();

        // build payload
        return maskedEntries.stream()
            .map(pair -> pair.getKey() + "=" + pair.getValue())
            .collect(Collectors.joining("&"));
    }

    private void modifyJsonTree(JsonNode root, Set<String> maskedFields) {
        modifyJsonTree(root, maskedFields, false);
    }

    private void modifyJsonTree(JsonNode root, Set<String> maskedFields, boolean maskArray) {
        if (root.isObject()) {
            Iterator<String> fields = root.fieldNames();

            while (fields.hasNext()) {
                String fieldName = fields.next();
                JsonNode fieldValue = root.get(fieldName);
                boolean fieldMatches = maskedFields.contains(fieldName);
                if (fieldValue.isValueNode() && fieldMatches) {
                    ((ObjectNode) root).set(fieldName, new TextNode(CamelConstants.MASKING_TEMPLATE));
                } else {
                    modifyJsonTree(fieldValue, maskedFields, fieldMatches);
                }
            }
        } else if (root.isArray()) {
            ArrayNode arrayNode = (ArrayNode) root;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode arrayElement = arrayNode.get(i);
                if (arrayElement.isValueNode() && maskArray) {
                    arrayNode.set(i, new TextNode(CamelConstants.MASKING_TEMPLATE));
                } else {
                    modifyJsonTree(arrayElement, maskedFields, false);
                }
            }
        }
    }

    private void modifyXmlTree(Node root, Set<String> maskedFields) {
        if (root.getNodeType() == Node.ELEMENT_NODE) {
            NodeList fields = root.getChildNodes();
            int childrenCount = fields.getLength();
            if (childrenCount == 0 && root.hasAttributes()) {
                maskAttributes(root, maskedFields);
            }
            for (int i = 0; i < childrenCount; i++) {
                Node child = fields.item(i);
                if (child.getNodeType() == Node.TEXT_NODE && childrenCount == 1) {
                    if (maskedFields.contains(root.getNodeName())) {
                        child.setTextContent(CamelConstants.MASKING_TEMPLATE);
                    }
                    if (root.hasAttributes()) {
                        maskAttributes(root, maskedFields);
                    }
                } else {
                    modifyXmlTree(child, maskedFields);
                }
            }
        }
    }

    private void maskAttributes(Node root, Set<String> maskedFields) {
        NamedNodeMap namedNodeMap = root.getAttributes();
        maskedFields.forEach(mf -> {
            Node childAttribute = namedNodeMap.getNamedItem(mf);
            if (childAttribute != null) {
                childAttribute.setTextContent(CamelConstants.MASKING_TEMPLATE);
            }
        });
    }

}
