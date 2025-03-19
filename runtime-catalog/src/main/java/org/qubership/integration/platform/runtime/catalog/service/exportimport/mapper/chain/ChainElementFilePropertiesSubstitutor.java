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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.internal.lang3.tuple.Pair;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.chain.ChainElementExternalEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;

@Component
public class ChainElementFilePropertiesSubstitutor {

    private final ObjectMapper objectMapper;

    public ChainElementFilePropertiesSubstitutor(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void enrichElementWithFileProperties(ChainElement element, File chainFilesDir, @Nullable String propertiesFilename) {
        try {
            Objects.requireNonNull(chainFilesDir, "Chain directory file must not be null");

            restoreProperties(element, chainFilesDir, propertiesFilename);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read element properties file: " + e.getMessage(), e);
        }
    }

    public Map<String, byte[]> getElementPropertiesAsSeparateFiles(ChainElementExternalEntity externalElement) {
        Map<String, byte[]> result = new HashMap<>();

        if (MapUtils.isEmpty(externalElement.getProperties())) {
            return result;
        }

        try {
            extractPropertyToSeparateFile(externalElement).ifPresent(property -> result.put(property.getKey(), property.getValue()));

            if (SERVICE_CALL.equals(externalElement.getType())) {
                result.putAll(extractServiceCallPropertiesToSeparateFiles(externalElement));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to save element properties to separate files: " + e.getMessage(), e);
        }

        return result;
    }

    private void restoreProperties(ChainElement element, File chainFilesDir, String propertiesFilename) throws IOException {
        if (!SERVICE_CALL.equals(element.getType())) {
            if (propertiesFilename == null) {
                return;
            }

            Object propertiesFileContent = extractPropertiesFileContent(element.getProperties(), chainFilesDir, propertiesFilename);
            if (propertiesFileContent instanceof Map<?, ?>) {
                element.getProperties().putAll((Map<String, Object>) propertiesFileContent);
            } else {
                element.getProperties().put(
                        (String) element.getProperties().get(PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY),
                        propertiesFileContent
                );
            }
            element.getProperties().remove(FILE_NAME_PROPERTY);
            return;
        }

        List<Map<String, Object>> afterPropertiesList = (List<Map<String, Object>>) element.getProperties()
                .getOrDefault(AFTER, Collections.emptyList());
        for (Map<String, Object> afterProperties : afterPropertiesList) {
            String afterPropertiesFilename = (String) afterProperties.get(FILE_NAME_PROPERTY);
            if (afterPropertiesFilename == null) {
                continue;
            }
            addServiceCallHandlerContent(
                    afterProperties,
                    extractPropertiesFileContent(afterProperties, chainFilesDir, afterPropertiesFilename)
            );
            afterProperties.remove(FILE_NAME_PROPERTY);
        }

        Map<String, Object> beforeProperties = (Map<String, Object>) element.getProperties()
                .getOrDefault(BEFORE, Collections.emptyMap());
        String beforePropertiesFilename = (String) beforeProperties.get(FILE_NAME_PROPERTY);
        if (beforePropertiesFilename == null) {
            return;
        }

        addServiceCallHandlerContent(
                beforeProperties,
                extractPropertiesFileContent(beforeProperties, chainFilesDir, beforePropertiesFilename)
        );
        beforeProperties.remove(FILE_NAME_PROPERTY);
    }

    private Object extractPropertiesFileContent(Map<String, Object> properties, File chainFilesDir, String propertiesFilename) throws IOException {
        String fileContent = ExportImportUtils.getFileContentByName(chainFilesDir, propertiesFilename);
        if (fileContent == null) {
            throw new IllegalArgumentException("Could not find file with properties: " + propertiesFilename);
        }

        if (isPropertiesFileGroovy(propertiesFilename, properties) || isPropertiesFileSql(propertiesFilename, properties)) {
            return fileContent;
        }
        if (isPropertiesFileJson(propertiesFilename, properties)) {
            return objectMapper.readValue(fileContent, new TypeReference<Map<String, Object>>() {});
        }

        throw new IllegalArgumentException(
                "The " + propertiesFilename + " properties file must have one of the following extensions: groovy, json or sql");
    }

    private void addServiceCallHandlerContent(Map<String, Object> handlerProperties, Object handlerContent) {
        Object type = handlerProperties.get(TYPE);
        if (SCRIPT.equals(type)) {
            handlerProperties.put(SCRIPT, handlerContent);
        }
        if (String.valueOf(type).startsWith(MAPPER)) {
            HashMap<String, Object> mapperContent = (HashMap<String, Object>) handlerContent;
            if (MAPPER.equals(type)) {
                handlerProperties.put(MAPPING, mapperContent.get(MAPPING));
                handlerProperties.put(SOURCE, mapperContent.get(SOURCE));
                handlerProperties.put(TARGET, mapperContent.get(TARGET));
            } else {
                handlerProperties.put(MAPPING_DESCRIPTION, mapperContent.get(MAPPING_DESCRIPTION));
            }
        }
    }

    private List<String> getPropertiesToExportInSeparateFile(ChainElementExternalEntity externalElement) {
        Map<String, Object> properties = externalElement.getProperties();

        String[] propertyNames = Optional.ofNullable((String) properties.get(PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY))
                .map(props -> props.replace(" ", "").split(",", -1))
                .orElse(new String[0]);

        return Arrays.asList(propertyNames);
    }

    private Optional<Pair<String, byte[]>> extractPropertyToSeparateFile(ChainElementExternalEntity externalElement)
            throws JsonProcessingException {
        Map<String, Object> properties = externalElement.getProperties();
        List<String> propsToExportSeparately = getPropertiesToExportInSeparateFile(externalElement);
        if (!CollectionUtils.isEmpty(propsToExportSeparately)) {
            String propertyContent = null;
            if (isPropertiesFileGroovy("", properties) || isPropertiesFileSql("", properties)) {
                propertyContent = Objects.toString(properties.get(propsToExportSeparately.get(0)), "");
            } else if (isPropertiesFileJson("", properties)) {
                Map<String, Object> propsToExportSeparatelyMap = properties.keySet().stream()
                        .filter(p -> propsToExportSeparately.contains(p) && properties.get(p) != null)
                        .collect(Collectors.toMap(p -> p, properties::get));
                if (!MapUtils.isEmpty(propsToExportSeparatelyMap)) {
                    propertyContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(propsToExportSeparatelyMap);
                }
            } else {
                throw new IllegalArgumentException("Invalid property '" + EXPORT_FILE_EXTENSION_PROPERTY
                        + "' of element " + externalElement.getId());
            }

            if (propertyContent != null) {
                String propertiesFileName = generatePropertiesFileName(externalElement, propsToExportSeparately);
                properties.put(FILE_NAME_PROPERTY, propertiesFileName);
                propsToExportSeparately.forEach(properties::remove);

                return Optional.of(Pair.of(propertiesFileName, propertyContent.getBytes()));
            }
        }

        return Optional.empty();
    }

    private Map<String, byte[]> extractServiceCallPropertiesToSeparateFiles(ChainElementExternalEntity externalElement) throws JsonProcessingException {
        Map<String, byte[]> result = new HashMap<>();
        Map<String, Object> properties = externalElement.getProperties();

        List<Map<String, Object>> afterPropertyList = (List<Map<String, Object>>) properties.getOrDefault(AFTER, Collections.emptyList());
        for (Map<String, Object> afterProperty : afterPropertyList) {
            String fileName;
            String propertyContent;
            if (SCRIPT.equals(afterProperty.get(TYPE))) {
                fileName = generateAfterScriptFileName(externalElement.getId(), afterProperty);
                propertyContent = afterProperty.get(SCRIPT) != null ? afterProperty.get(SCRIPT).toString() : "";
                afterProperty.remove(SCRIPT);
            } else if (StringUtils.startsWith((String) afterProperty.get(TYPE), MAPPER)) {
                fileName = generateAfterMapperFileName(externalElement.getId(), afterProperty);
                propertyContent = extractPropertyStringForMapper(afterProperty);
            } else {
                continue;
            }

            afterProperty.put(FILE_NAME_PROPERTY, fileName);
            result.put(fileName, propertyContent.getBytes());
        }

        Map<String, Object> beforeProperty = (Map<String, Object>) properties.getOrDefault(BEFORE, Collections.emptyMap());
        String fileName;
        String propertyContent;
        if (SCRIPT.equals(beforeProperty.get(TYPE))) {
            fileName = generateBeforeScriptFileName(externalElement.getId());
            propertyContent = beforeProperty.get(SCRIPT) != null ? beforeProperty.get(SCRIPT).toString() : "";
            beforeProperty.remove(SCRIPT);
        } else if (StringUtils.startsWith((String) beforeProperty.get(TYPE), MAPPER)) {
            fileName = generateBeforeMapperFileName(externalElement.getId());
            propertyContent = extractPropertyStringForMapper(beforeProperty);
        } else {
            return result;
        }

        beforeProperty.put(FILE_NAME_PROPERTY, fileName);
        result.put(fileName, propertyContent.getBytes());

        return result;
    }

    private String generatePropertiesFileName(ChainElementExternalEntity externalElement, List<String> propsToExportInSeparateFile) {
        String prefix;

        if (externalElement.getType() != null && externalElement.getType().startsWith(MAPPER)) {
            prefix = propsToExportInSeparateFile.size() == 1 ? propsToExportInSeparateFile.get(0) : "mapper";
        } else {
            prefix = propsToExportInSeparateFile.size() == 1 ? propsToExportInSeparateFile.get(0) : "properties";
        }

        String extension = Optional.ofNullable(externalElement.getProperties().get(EXPORT_FILE_EXTENSION_PROPERTY))
                .map(Object::toString)
                .orElse(DEFAULT_EXTENSION);

        return prefix + "-" + externalElement.getId() + "." + extension;
    }

    private String generateAfterScriptFileName(String id, Map<String, Object> afterProp) {
        return SCRIPT + DASH + getIdOrCode(afterProp) + DASH + id + "." + GROOVY_EXTENSION;
    }

    private String generateBeforeScriptFileName(String id) {
        return SCRIPT + DASH + BEFORE + DASH + id + "." + GROOVY_EXTENSION;
    }

    private String generateAfterMapperFileName(String id, Map<String, Object> afterProp) {
        return MAPPING_DESCRIPTION + DASH + getIdOrCode(afterProp) + DASH + id + "." + JSON_EXTENSION;
    }

    private String generateBeforeMapperFileName(String id) {
        return MAPPING_DESCRIPTION + DASH + BEFORE + DASH + id + "." + JSON_EXTENSION;
    }

    private Object getIdOrCode(Map<String, Object> mapProp) {
        return mapProp.get(ID) == null ? mapProp.get(CODE) : mapProp.get(ID);
    }

    private String extractPropertyStringForMapper(Map<String, Object> properties) throws JsonProcessingException {
        String propertyContent = "";
        List<String> props = List.of(MAPPING_DESCRIPTION, MAPPING, SOURCE, TARGET);
        Map<String, Object> propsToExportSeparatelyMap = properties.keySet().stream()
                .filter(p -> props.stream().anyMatch(p1 -> p1.equals(p)) && properties.get(p) != null)
                .collect(Collectors.toMap(p -> p, properties::get));
        if (!MapUtils.isEmpty(propsToExportSeparatelyMap)) {
            propertyContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(propsToExportSeparatelyMap);
        }
        properties.remove(MAPPING_DESCRIPTION);
        properties.remove(MAPPING);
        properties.remove(SOURCE);
        properties.remove(TARGET);

        return propertyContent;
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
