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

package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.atlasmap.api.AtlasContext;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.core.DefaultAtlasFunctionResolver;
import io.atlasmap.json.v2.JsonDataSource;
import io.atlasmap.v2.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.text.StringEscapeUtils;
import org.qubership.integration.platform.engine.mapper.atlasmap.CustomAtlasContext;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Processor is used to receive input body and map it to
 * another structure with rules, that is placed at the internalProperty_mappingConfiguration
 * environment variable
 */
@Slf4j
@Getter
@Setter
@Component
public class MapperProcessor implements Processor {

    private static final String CAMEL_EXCHANGE_PROPERTY = "camelExchangeProperty";
    private static final String CAMEL_MESSAGE_HEADER = "current";
    private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
    private static final String CONTENT_TYPE_JSON_VALUE = "application/json";
    private static final String CONTENT_TYPE_XML_VALUE = "application/xml";
    private static final String TARGET_DOC_ID = "target";
    private static final String SOURCE_DOC_ID = "source";
    private static final String VARIABLES_PROPERTY = "variables";
    private static final String UNABLE_TO_READ_PROPERTY_ERROR_MESSAGE = "Unable to read complex property: ";

    private final DefaultAtlasContextFactory factory;
    private final ObjectMapper objectMapper;

    @Autowired
    public MapperProcessor(@Qualifier("jsonMapper") ObjectMapper objectMapper) {
        DefaultAtlasFunctionResolver.getInstance(); // To fix time when function factories are loaded
        this.factory = DefaultAtlasContextFactory.getInstance();
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String mapping = exchange.getProperty(Properties.MAPPING_CONFIG, String.class);
        mapping = StringEscapeUtils.unescapeXml(mapping);
        StringReader stringReader = new StringReader(mapping);

        AtlasMapping atlasMapping = objectMapper.readValue(stringReader, AtlasMapping.class);
        AtlasContext context = new CustomAtlasContext(factory, atlasMapping);
        AtlasSession session = context.createSession();
        uploadProperties(exchange, session);
        setDataSourcesDocuments(exchange, atlasMapping, session);

        context.process(session);
        logIssues(exchange, session);

        throwExceptionOnErrors(exchange, session);

        setUpOutputContentType(exchange, atlasMapping);
        downloadProperties(exchange, session);
        getDataSourcesDocuments(exchange, atlasMapping, session);
    }

    private void throwExceptionOnErrors(Exchange exchange, AtlasSession session) throws Exception {
        Boolean throwException = exchange.getProperty(Properties.MAPPING_THROW_EXCEPTION, false, Boolean.class);
        if (throwException) {
            throwExceptionOnTransformationErrors(session);
        }
        throwExceptionOnUnsupportedErrors(session);
    }

    private void logIssues(Exchange exchange, AtlasSession session) {
        String sessionId = exchange.getProperty(Properties.SESSION_ID, String.class);

        List<Audit> audits = session.getAudits().getAudit();
        audits.stream()
                .filter(audit -> AuditStatus.ERROR == audit.getStatus() || AuditStatus.WARN == audit.getStatus())
                .filter(audit -> !isValidByDesign(audit))
                .forEach(audit -> {
                    AuditStatus status = audit.getStatus();
                    String message = audit.getMessage();
                    String path = audit.getPath();
                    log.debug("Mapper issue for session {}. " +
                              "\nAudit message: {}" +
                              "\nIssue status: {}" +
                              "\nPath: {}", sessionId, message, status, path);
                });
    }

    private void uploadProperties(Exchange exchange, AtlasSession session) {
        Map<String, Object> propertiesMap = exchange.getProperties();
        Map<String, Object> headersMap = exchange.getMessage().getHeaders();
        session.getMapping().getProperties().getProperty().forEach(property -> {
            String propertyName = property.getName();
            if (property.getDataSourceType().equals(DataSourceType.SOURCE)) {
                if (property.getScope().equals(CAMEL_EXCHANGE_PROPERTY)) {
                    if (propertiesMap.containsKey(propertyName)) {
                        session.getSourceProperties().put(propertyName, propertiesMap.get(propertyName));
                    }
                }
                if (property.getScope().equals(CAMEL_MESSAGE_HEADER)) {
                    if (headersMap.containsKey(propertyName)) {
                        session.getSourceProperties().put(propertyName, headersMap.get(propertyName));
                    }
                }
            }
            if (property.getDataSourceType().equals(DataSourceType.TARGET)) {
                if (property.getScope().equals(CAMEL_EXCHANGE_PROPERTY)) {
                    if (propertiesMap.containsKey(propertyName)) {
                        session.getTargetProperties().put(propertyName, propertiesMap.get(propertyName));
                    }
                }
                if (property.getScope().equals(CAMEL_MESSAGE_HEADER)) {
                    if (headersMap.containsKey(propertyName)) {
                        session.getTargetProperties().put(propertyName, headersMap.get(propertyName));
                    }
                }
            }
        });
    }

    private void downloadProperties(Exchange exchange, AtlasSession session) {
        session.getMapping().getProperties().getProperty().forEach(property -> {
            if (property.getDataSourceType().equals(DataSourceType.TARGET)) {
                Object propertyValue = session.getTargetProperties().get(property.getName());
                if (property.getScope().equals(CAMEL_EXCHANGE_PROPERTY) && propertyValue != null) {
                    exchange.getProperties().put(property.getName(), propertyValue);
                }
                if (property.getScope().equals(CAMEL_MESSAGE_HEADER) && propertyValue != null) {
                    exchange.getMessage().getHeaders().put(property.getName(), propertyValue);
                }
            }
        });
    }

    private void setDataSourcesDocuments(Exchange exchange, AtlasMapping atlasMapping, AtlasSession session) {
        String body = exchange.getMessage().getBody(String.class);
        session.setSourceDocument(SOURCE_DOC_ID, body);
        atlasMapping
                .getDataSource()
                .stream()
                .filter(dataSource -> !dataSource.getId().equals(SOURCE_DOC_ID) && !dataSource.getId().equals(TARGET_DOC_ID))
                .filter(dataSource -> dataSource.getDataSourceType().equals(DataSourceType.SOURCE))
                .forEach(propertyDataSource -> {
                    String propertyName = propertyDataSource.getName();
                    Object propertyValue = propertyName.equals(VARIABLES_PROPERTY)
                            ? getVariablesPropertyValue((HashMap<String, String>) exchange.getProperty(propertyName))
                            : exchange.getProperty(propertyName);
                    try {
                        session.setSourceDocument(propertyName, propertyValue != null ? objectMapper.writeValueAsString(propertyValue) : null);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(UNABLE_TO_READ_PROPERTY_ERROR_MESSAGE.concat(propertyName), e);
                    }
                });
    }

    private ObjectNode getVariablesPropertyValue(HashMap<String, String> variables) {
        ObjectNode rootObject = objectMapper.createObjectNode();
        variables.keySet().forEach(
                key -> {
                    String value = variables.get(key);
                    JsonNode valueObject;
                    try {
                        valueObject = objectMapper.readTree(value);
                    } catch (JsonProcessingException e) {
                        valueObject = new TextNode(value);
                    }
                    rootObject.set(key, valueObject);
                }
        );
        return rootObject;
    }

    private void getDataSourcesDocuments(Exchange exchange, AtlasMapping atlasMapping, AtlasSession session) {
        Object target = session.getTargetDocument(TARGET_DOC_ID);
        exchange.getMessage().setBody(target);
        atlasMapping
                .getDataSource()
                .stream()
                .filter(dataSource -> !dataSource.getId().equals(SOURCE_DOC_ID) && !dataSource.getId().equals(TARGET_DOC_ID))
                .filter(dataSource -> dataSource.getDataSourceType().equals(DataSourceType.TARGET))
                .forEach(propertyDataSource -> {
                    String propertyName = propertyDataSource.getName();
                    Object propertyValue = session.getTargetDocument(propertyName);
                    exchange.setProperty(propertyName, propertyValue);
                });

    }

    private void setUpOutputContentType(Exchange exchange, AtlasMapping atlasMapping) {
        DataSource targetDataSource = atlasMapping
                .getDataSource()
                .stream()
                .filter(dataSource -> dataSource.getDataSourceType().equals(DataSourceType.TARGET))
                .findFirst()
                .orElse(new JsonDataSource());

        String targetContentType = targetDataSource instanceof JsonDataSource ? CONTENT_TYPE_JSON_VALUE : CONTENT_TYPE_XML_VALUE;
        exchange.getMessage().setHeader(CONTENT_TYPE_HEADER_NAME, targetContentType);
    }

    private void throwExceptionOnTransformationErrors(AtlasSession session) throws Exception {
        Collection<Audit> transformationErrors = session.getAudits().getAudit().stream()
                .filter(this::isTransformationError)
                .toList();
        if (!transformationErrors.isEmpty()) {
            String message = transformationErrors.stream()
                    .map(audit -> String.format("path: %s, message: \"%s\"", audit.getPath(), audit.getMessage()))
                    .collect(Collectors.joining("; ", "Failed to perform mapping: ", ""));
            throw new Exception(message);
        }
    }

    private boolean isTransformationError(Audit audit) {
        return AuditStatus.ERROR.equals(audit.getStatus())
                && (audit.getMessage().startsWith("Failed to apply field action: ")
                || audit.getMessage().startsWith("Failed to convert field value ")
                || audit.getMessage().startsWith("Expression processing error "));
    }

    private boolean isValidByDesign(Audit audit) {
        return (AuditStatus.WARN.equals(audit.getStatus())
                        && audit.getMessage().startsWith("The 0 index will be used for any extra parent collections in target"))
                || (AuditStatus.WARN.equals(audit.getStatus())
                        && audit.getMessage().startsWith("Null or non-String source document"))
                || (AuditStatus.ERROR.equals(audit.getStatus())
                        && audit.getMessage().startsWith("Cannot read field")
                        && audit.getMessage().endsWith("document is null"))
                || (AuditStatus.ERROR.equals(audit.getStatus())
                        && audit.getMessage().startsWith("Cannot read a field")
                        && audit.getMessage().endsWith("document is null"));
    }

    private void throwExceptionOnUnsupportedErrors(AtlasSession session) {
        Collection<Audit> transformationErrors = session.getAudits().getAudit().stream()
                .filter(this::isUnsupportedError)
                .toList();
        if (!transformationErrors.isEmpty()) {
            String message = transformationErrors.stream()
                    .map(audit -> String.format("path: %s, message: \"%s\"", audit.getPath(), audit.getMessage()))
                    .collect(Collectors.joining("; ", "Failed to perform mapping: ", ""));
            throw new UnsupportedOperationException(message);
        }
    }

    private boolean isUnsupportedError(Audit audit) {
        return AuditStatus.ERROR.equals(audit.getStatus())
                && (audit.getMessage().startsWith("Nested JSON array is not supported")
        );
    }
}
