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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.chain.ChainElementsExternalEntityMapper;
import org.springframework.boot.jackson.JsonComponent;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.*;

import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportConstants.*;
import static org.qubership.integration.platform.catalog.service.exportimport.ExportImportUtils.*;

@Slf4j
@JsonComponent
@Deprecated(since = "2023.4")
public class ChainElementSerializer extends StdSerializer<ChainElement> {

    public ChainElementSerializer() {
        this(null);
    }

    public ChainElementSerializer(Class<ChainElement> t) {
        super(t);
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public void serialize(ChainElement element, JsonGenerator generator, SerializerProvider serializer) throws IOException {
        try {
            generator.writeStartObject();

            generator.writeStringField(ID, element.getId());
            if (element.getName() != null) {
                generator.writeStringField(NAME, element.getName());
            }
            if (element.getDescription() != null) {
                generator.writeStringField(DESCRIPTION, element.getDescription());
            }
            if (element.getType() != null) {
                generator.writeStringField(ELEMENT_TYPE, element.getType());
            }
            if (element.getSwimlane() != null) {
                generator.writeStringField(SWIMLANE_ID, element.getSwimlane().getId());
            }

            writeProperties(element, generator);

            if (element instanceof ContainerChainElement container && !CollectionUtils.isEmpty(container.getElements())) {
                generator.writeObjectField(CHILDREN, container.getElements());
            }

            generator.writeEndObject();
        } catch (IOException e) {
            log.warn("Exception while serializing ChainElement {}, exception: ", element.getId(), e);
            throw e;
        }
    }

    private void writeProperties(ChainElement element, JsonGenerator generator) throws IOException {
        Map<String, Object> properties = new TreeMap<>(
                ChainElementsExternalEntityMapper.preSortProperties(element.getProperties()));

        if (!CollectionUtils.isEmpty(properties)) {
            List<String> propsToExportSeparately = getPropertiesToExportInSeparateFile(element);
            if (!CollectionUtils.isEmpty(propsToExportSeparately)) {
                if (isPropertiesFileGroove(element.getProperties()) || isPropertiesFileSql(element.getProperties())
                        || propsToExportSeparately.stream().anyMatch(p -> properties.get(p) != null)) {
                    generator.writeObjectField(FILE_NAME_PROPERTY, generatePropertiesFileName(element));
                }
            }
            properties.keySet().removeAll(propsToExportSeparately);
            if (SERVICE_CALL.equals(element.getType())) {
                writeFileNameInProperty(element, properties, generator);
            } else {
                generator.writeObjectField("properties", properties);
            }
        }
    }

    private void writeFileNameInProperty(ChainElement element, Map<String, Object> properties,
                                         JsonGenerator generator) throws IOException {
        List<Map<String, Object>> afterPropertyList = (List<Map<String, Object>>) properties.get(AFTER);
        Map<String, Object> beforePropMap = (Map<String, Object>) properties.get(BEFORE);
        Map<String, Object> scriptContent = new HashMap<>();
        if (!CollectionUtils.isEmpty(afterPropertyList)) {
            for (Map<String, Object> afterProp : afterPropertyList) {
                if (SCRIPT.equals(afterProp.get(TYPE))) {
                    setScriptFileNameInProperty((String) getIdOrCode(afterProp), afterProp,
                            generateAfterScriptFileName(element.getId(), afterProp), scriptContent);
                } else if (null != afterProp.get(TYPE) && StringUtils.contains((String) afterProp.get(TYPE), MAPPER)) {
                    setMapperFileNameInProperty((String) getIdOrCode(afterProp), afterProp,
                            generateAfterMapperFileName(element.getId(), afterProp), scriptContent);
                } else if (null == afterProp.get(TYPE)) {
                    afterProp.remove(FILE_NAME_PROPERTY);
                }
            }
        }
        if (!CollectionUtils.isEmpty(beforePropMap)) {
            if (SCRIPT.equals(beforePropMap.get(TYPE))) {
                setScriptFileNameInProperty(BEFORE, beforePropMap,
                        generateBeforeScriptFileName(element.getId()), scriptContent);
            } else if (null != beforePropMap.get(TYPE)) {
                if (MAPPER.equals(beforePropMap.get(TYPE))) {
                    setMapperFileNameInProperty(MAPPING, beforePropMap,
                            generateBeforeMapperFileName(element.getId(), beforePropMap), scriptContent);
                } else {
                    setMapperFileNameInProperty(MAPPING_DESCRIPTION, beforePropMap,
                            generateBeforeMapperFileName(element.getId(), beforePropMap), scriptContent);
                }
            }
        }
        generator.writeObjectField(PROPERTIES, properties);
        if (scriptContent.get(BEFORE) != null) {
            beforePropMap.put(SCRIPT, scriptContent.get(BEFORE));
        } else if (scriptContent.get(MAPPING_DESCRIPTION) != null) {
            beforePropMap.put(MAPPING_DESCRIPTION, scriptContent.get(MAPPING_DESCRIPTION));
        } else if (scriptContent.get(MAPPING) != null) {
            beforePropMap.put(MAPPING, scriptContent.get(MAPPING));
            setSourceAndTargetValue(beforePropMap, scriptContent);
        }
        if (!CollectionUtils.isEmpty(afterPropertyList)) {
            for (Map<String, Object> afterProp : afterPropertyList) {
                if (scriptContent.get(getIdOrCode(afterProp)) != null && (SCRIPT.equals(afterProp.get(TYPE)))) {
                    afterProp.put(SCRIPT, scriptContent.get(getIdOrCode(afterProp)));
                } else if (scriptContent.get(getIdOrCode(afterProp)) != null && null != afterProp.get(TYPE)) {
                    if (MAPPER.equals(afterProp.get(TYPE))) {
                        afterProp.put(MAPPING, scriptContent.get(getIdOrCode(afterProp)));
                        setSourceAndTargetValue(afterProp, scriptContent);
                    } else {
                        afterProp.put(MAPPING_DESCRIPTION, scriptContent.get(getIdOrCode(afterProp)));
                    }
                }
            }
        }
    }

    private void setSourceAndTargetValue(Map<String, Object> mapperProp, Map<String, Object> scriptContent) {
        mapperProp.put(SOURCE, scriptContent.get(SOURCE));
        mapperProp.put(TARGET, scriptContent.get(TARGET));
    }

    private void setScriptFileNameInProperty(String key, Map<String, Object> properties, String fileName, Map<String, Object> scriptContent) {
        scriptContent.put(key, properties.get(SCRIPT));
        properties.keySet().removeAll(Arrays.asList(SCRIPT));
        properties.put(FILE_NAME_PROPERTY, fileName);
    }

    private void setMapperFileNameInProperty(String key, Map<String, Object> properties, String fileName, Map<String, Object> scriptContent) {
        if (MAPPER.equals(properties.get(TYPE))){
            scriptContent.put(key, properties.get(MAPPING));
            setSourceAndTargetValue(scriptContent, properties);
            properties.keySet().removeAll(Arrays.asList(MAPPING, SOURCE, TARGET));
        } else {
            scriptContent.put(key, properties.get(MAPPING_DESCRIPTION));
            properties.keySet().removeAll(Arrays.asList(MAPPING_DESCRIPTION));
        }
        properties.put(FILE_NAME_PROPERTY, fileName);
    }
}
