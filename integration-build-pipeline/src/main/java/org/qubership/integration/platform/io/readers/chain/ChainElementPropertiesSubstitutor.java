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

package org.qubership.integration.platform.io.readers.chain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.io.readers.chain.ImportConstants.*;

/**
 * Merges separately exported property files back into an element's inline properties.
 *
 * <p>The exporter moves large or free-form properties (scripts, mappings, JSON blobs) into their own
 * files under the chain directory and leaves a {@code propertiesFilename} reference behind. This class
 * reverses that on import, mirroring the catalog's {@code ChainElementFilePropertiesSubstitutor}
 * restore path but operating on the import DTO rather than a persisted entity.
 */
@Component
@Slf4j
public class ChainElementPropertiesSubstitutor {

    private final ObjectMapper objectMapper;

    public ChainElementPropertiesSubstitutor(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Restores an element's file-backed properties from the given source. Does nothing when the
     * source is {@code null}, which lets the mapper run without a chain directory.
     */
    public void enrichElementWithFileProperties(ChainElementExternalEntity element, @Nullable PropertyFileSource fileSource) {
        if (fileSource == null) {
            return;
        }
        try {
            restoreProperties(element, fileSource);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read element properties file: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreProperties(ChainElementExternalEntity element, PropertyFileSource fileSource) throws IOException {
        Map<String, Object> properties = element.getProperties();

        if (!SERVICE_CALL.equals(element.getType())) {
            String propertiesFilename = element.getPropertiesFilename();
            if (propertiesFilename == null) {
                propertiesFilename = (String) properties.get(FILE_NAME_PROPERTY);
            }
            if (propertiesFilename == null) {
                return;
            }

            Object propertiesFileContent = extractPropertiesFileContent(properties, fileSource, propertiesFilename);
            if (propertiesFileContent instanceof Map<?, ?>) {
                properties.putAll((Map<String, Object>) propertiesFileContent);
            } else {
                properties.put(
                        (String) properties.get(PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY),
                        propertiesFileContent
                );
            }
            properties.remove(FILE_NAME_PROPERTY);
            return;
        }

        Object afterPropertiesObj = properties.getOrDefault(AFTER, Collections.emptyList());
        if (afterPropertiesObj instanceof List<?> afterPropertiesList) {
            for (Object obj : afterPropertiesList) {
                if (obj instanceof Map<?, ?> afterProperties) {
                    String afterPropertiesFilename = (String) afterProperties.get(FILE_NAME_PROPERTY);
                    if (afterPropertiesFilename == null) {
                        continue;
                    }
                    addServiceCallHandlerContent(
                            (Map<String, Object>) afterProperties,
                            extractPropertiesFileContent((Map<String, Object>) afterProperties, fileSource, afterPropertiesFilename)
                    );
                    afterProperties.remove(FILE_NAME_PROPERTY);
                } else {
                    log.error("Either the 'after' property is missing, or it is not formatted as a key-value pair {}",
                            obj.getClass().getName());
                    throw new IllegalArgumentException("Either the 'after' property is missing, or it is not formatted as a key-value pair");
                }
            }
        } else {
            log.error("Either the 'after' property is missing or it is not in the required format {}",
                    afterPropertiesObj.getClass().getName());
            throw new IllegalArgumentException("Either the 'after' property is missing or it is not in the required format");
        }

        Object beforePropertiesObj = properties.getOrDefault(BEFORE, Collections.emptyMap());
        if (beforePropertiesObj instanceof Map<?, ?> beforeProperties) {
            String beforePropertiesFilename = (String) beforeProperties.get(FILE_NAME_PROPERTY);
            if (beforePropertiesFilename == null) {
                return;
            }

            addServiceCallHandlerContent(
                    (Map<String, Object>) beforeProperties,
                    extractPropertiesFileContent((Map<String, Object>) beforeProperties, fileSource, beforePropertiesFilename)
            );
            beforeProperties.remove(FILE_NAME_PROPERTY);
        } else {
            log.error("Either the 'before' property is missing, or it is not formatted as a key-value pair {}",
                    beforePropertiesObj.getClass().getName());
            throw new IllegalArgumentException("Either the 'before' property is missing, or it is not formatted as a key-value pair");
        }
    }

    private Object extractPropertiesFileContent(Map<String, Object> properties, PropertyFileSource fileSource, String propertiesFilename)
            throws IOException {
        String fileContent = fileSource.getFileContent(propertiesFilename);
        if (fileContent == null) {
            throw new IllegalArgumentException("Could not find file with properties: " + propertiesFilename);
        }

        if (isPropertiesFileGroovy(propertiesFilename, properties) || isPropertiesFileSql(propertiesFilename, properties)) {
            return fileContent;
        }
        if (isPropertiesFileJson(propertiesFilename, properties)) {
            return objectMapper.readValue(fileContent, new TypeReference<Map<String, Object>>() {
            });
        }

        throw new IllegalArgumentException(
                "The " + propertiesFilename + " properties file must have one of the following extensions: groovy, json or sql");
    }

    @SuppressWarnings("unchecked")
    private void addServiceCallHandlerContent(Map<String, Object> handlerProperties, Object handlerContent) {
        Object type = handlerProperties.get(TYPE);
        if (SCRIPT.equals(type)) {
            handlerProperties.put(SCRIPT, handlerContent);
        }
        if (String.valueOf(type).startsWith(MAPPER)) {
            Map<String, Object> mapperContent = (Map<String, Object>) handlerContent;
            if (MAPPER.equals(type)) {
                handlerProperties.put(MAPPING, mapperContent.get(MAPPING));
                handlerProperties.put(SOURCE, mapperContent.get(SOURCE));
                handlerProperties.put(TARGET, mapperContent.get(TARGET));
            } else {
                handlerProperties.put(MAPPING_DESCRIPTION, mapperContent.get(MAPPING_DESCRIPTION));
            }
        }
    }

    private boolean isPropertiesFileJson(String fileName, Map<String, Object> properties) {
        return JSON_EXTENSION.equals(properties.get(EXPORT_FILE_EXTENSION_PROPERTY)) || fileName.endsWith(".json");
    }

    private boolean isPropertiesFileGroovy(String fileName, Map<String, Object> properties) {
        return GROOVY_EXTENSION.equals(properties.get(EXPORT_FILE_EXTENSION_PROPERTY)) || fileName.endsWith(".groovy");
    }

    private boolean isPropertiesFileSql(String fileName, Map<String, Object> properties) {
        return SQL_EXTENSION.equals(properties.get(EXPORT_FILE_EXTENSION_PROPERTY)) || fileName.endsWith(".sql");
    }
}
