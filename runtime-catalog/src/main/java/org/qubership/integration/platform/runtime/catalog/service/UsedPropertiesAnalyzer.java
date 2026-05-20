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

package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.model.chain.element.*;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.util.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@Transactional
public class UsedPropertiesAnalyzer {

    /**
     * Match examples:
     * <code>
     * <br/>exchange.message.headers.test1.tea
     * <br/>exchange.message.headers.test1
     * <br/>exchange.message.headers.'test1'
     * <br/>exchange.message.headers.'test1--daAAAa_123fsfwsf'
     * <br/>exchange.message.headers."test1"
     * <br/>exchange.message.headers['test1']
     * <br/>exchange.message.headers["test1"]
     * <br/>exchange.message.getHeader('test1')
     * <br/>exchange.message.getHeader("test1")
     * <br/>exchange.getMessage().headers.test1
     * <br/>exchange.getMessage().headers.'test1'
     * <br/>exchange.getMessage().headers."test1"
     * <br/>exchange.getMessage().headers['test1']
     * <br/>exchange.getMessage().headers["test1"]
     * <br/>exchange.getMessage().getHeader('test1')
     * <br/>exchange.getMessage().getHeader("test1")
     * <br/>exchange.getMessage().getHeaders().get('test1')
     * <br/>exchange.getMessage().getHeaders().get("test1")
     * <br/>exchange.message.getHeaders().get('test1')
     * <br/>exchange.message.getHeaders().get("test1")
     * </code>
     */
    private static final Pattern GROOVY_GET_HEADERS_PATTERN = Pattern.compile(
            "exchange\\.(message|getMessage\\(\\))\\.(headers|getHeader\\(|getHeaders\\(\\))"
                    + "((\\[(['\"]))|(\\.?((get\\(([\"']))|([\"'])?)))([a-zA-Z0-9_.\\-]+)(?!([^\\n\\r]*[=(]))", Pattern.CASE_INSENSITIVE);
    public static final int[] GROOVY_GET_HEADER_GROUPS = {11};
    /**
     * Match examples:
     * <code>
     * <br/>exchange.message.headers.test1 = "test_value"
     * <br/>exchange.message.headers['test1'] = "test_value"
     * <br/>exchange.message.headers["test1"] = "test_value"
     * <br/>exchange.message.setHeader('test1', "test_value")
     * <br/>exchange.message.setHeader("test1", "test_value")
     * <br/>exchange.getMessage().headers.test1 = "test_value"
     * <br/>exchange.getMessage().headers['test1'] = "test_value"
     * <br/>exchange.getMessage().headers["test1"] = "test_value"
     * <br/>exchange.getMessage().setHeader('test1', "test_value")
     * <br/>exchange.getMessage().setHeader("test1", "test_value")
     * <p>
     * <br/>exchange.getMessage().headers.remove('test1')
     * <br/>exchange.getMessage().headers.remove("test1")
     * <br/>exchange.message.headers.remove('test1')
     * <br/>exchange.message.headers.remove("test1")
     * <br/>exchange.message.removeHeader('test1')
     * <br/>exchange.message.removeHeader("test1")
     * <br/>exchange.getMessage().removeHeader('test1')
     * <br/>exchange.getMessage().removeHeader("test1")
     * </code>
     */
    private static final Pattern GROOVY_SET_HEADERS_PATTERN = Pattern.compile(
            "exchange\\.(message|getMessage\\(\\))\\.((headers([\\[.]['\"]?)([a-zA-Z0-9_.\\-]+)(['\"]]?)?\\s+=)|"
                    + "(setHeader\\(['\"]([a-zA-Z0-9_.\\-]+)(['\")]?))|"
                    + "(((getHeaders\\(\\)|headers)\\.remove|removeHeader)(\\(['\"])([a-zA-Z0-9_.\\-]+)))", Pattern.CASE_INSENSITIVE);
    public static final int[] GROOVY_SET_HEADER_GROUPS = {5, 8, 14};

    /**
     * Match examples:
     * <code>
     * <br/>exchange.properties.test1
     * <br/>exchange.properties.'test1'
     * <br/>exchange.properties."test1"
     * <br/>exchange.properties['test1']
     * <br/>exchange.properties["test1"]
     * <br/>exchange.getProperty('test1')
     * <br/>exchange.getProperty("test1")
     * </code>
     */
    private static final Pattern GROOVY_GET_PROPERTIES_PATTERN = Pattern.compile(
            "exchange\\.((properties|getProperty)((([\\[(])|(\\.))['\"]?)([a-zA-Z0-9_.\\-]+)(?!([^\\n\\r]*[=(])))", Pattern.CASE_INSENSITIVE);
    public static final int[] GROOVY_GET_PROPERTIES_GROUPS = {7};

    /**
     * Match examples:
     * <code>
     * <br/>exchange.properties.test1 = "test_value"
     * <br/>exchange.properties.'test1' = "test_value"
     * <br/>exchange.properties."test1" = "test_value"
     * <br/>exchange.properties['test1'] = "test_value"
     * <br/>exchange.properties["test1"] = "test_value"
     * <br/>exchange.setProperty('test1', "test_value")
     * <br/>exchange.setProperty("test1", "test_value")
     * <p>
     * <br/>exchange.properties.remove('test1')
     * <br/>exchange.properties.remove("test1")
     * <br/>exchange.getProperties().remove('test1')
     * <br/>exchange.getProperties().remove("test1")
     * <br/>exchange.removeProperty('test1')
     * <br/>exchange.removeProperty("test1")
     * </code>
     */
    private static final Pattern GROOVY_SET_PROPERTIES_PATTERN = Pattern.compile(
            "exchange\\.((properties[\\[.]['\"]?([a-zA-Z0-9_.\\-]+)(['\"]]?)?\\s+=)|"
                    + "(setProperty\\(['\"]([a-zA-Z0-9_.\\-]+)['\"]\\)?)|"
                    + "(properties\\.remove|getProperties\\(\\)\\.remove|removeProperty)(\\(['\"])([a-zA-Z0-9_.\\-]+))", Pattern.CASE_INSENSITIVE);
    public static final int[] GROOVY_SET_PROPERTIES_GROUPS = {3, 6, 9};

    /**
     * Match examples:
     * <code>
     * <br/>${exchangeProperty.foo}
     * <br/>${exchangeProperty.foo.size()}
     * <br/>${exchangeProperty.'foo'.size()}
     * <br/>${exchangeProperty.foo?.bar}
     * <br/>${exchangeProperty[foo]}
     * <br/>${exchangeProperty['foo']}
     * <br/>${exchangeProperty[foo]?.bar}
     * <br/>${exchangeProperty[foo.bar]}
     * <br/>${exchangeProperty['foo']}
     * <br/>${exchangeProperty['foo.bar']}
     * </code>
     */
    private static final Pattern PROPS_SIMPLE_PATTERN = Pattern.compile(
            "\\$\\{exchangeProperty((\\.([a-zA-Z0-9_\\-]+))|([.\\[]'?([a-zA-Z0-9_.\\-]+)))[^}]*}", Pattern.CASE_INSENSITIVE);
    public static final int[] EX_PROP_GROUPS = {3, 5};

    /**
     * Match examples:
     * <code>
     * <br/>${header(s).foo}
     * <br/>${header(s).foo.size()}
     * <br/>${header(s).'foo'}
     * <br/>${header(s).foo?.bar}
     * <br/>${header(s)[foo]}
     * <br/>${header(s)[foo.bar]}
     * <br/>${header(s)['foo']}
     * <br/>${header(s)['foo']?.bar}
     * <br/>${headerAs('foo', Type)}
     * </code>
     */
    private static final Pattern HEADERS_SIMPLE_PATTERN = Pattern.compile(
            "\\$\\{headera?s?((\\.([a-zA-Z0-9_\\-]+))|([.\\[(]'?([a-zA-Z0-9_.\\-]+)))[^}]*}", Pattern.CASE_INSENSITIVE);
    public static final int[] EX_HEADER_GROUPS = {3, 5};

    private static final Set<String> ELEMENTS_WITH_SCRIPT = Set.of(
            CamelNames.SCRIPT, CamelNames.SERVICE_CALL_COMPONENT, CamelNames.HTTP_TRIGGER_COMPONENT);
    private static final Set<String> ELEMENTS_WITH_MAPPER = Set.of(
            CamelNames.MAPPER_2, CamelNames.SERVICE_CALL_COMPONENT, CamelNames.HTTP_TRIGGER_COMPONENT);
    private static final Set<String> EXCLUDE_MAPPER_ELEMENTS = Set.of(
            CamelNames.MAPPER, CamelNames.MAPPER_2, CamelNames.SERVICE_CALL_COMPONENT, CamelNames.HTTP_TRIGGER_COMPONENT);

    public static final String MAPPING_DESCRIPTION = "mappingDescription";

    private final ElementRepository elementRepository;

    @Autowired
    public UsedPropertiesAnalyzer(ElementRepository elementRepository) {
        this.elementRepository = elementRepository;
    }

    public List<UsedProperty> getUsedProperties(String chainId) {
        List<ChainElement> chainElements = elementRepository.findAllByChainId(chainId);
        // key = name + type
        Map<String, UsedProperty> usedProperties = new HashMap<>();
        for (ChainElement chainElement : chainElements) {
            findUsedProperties(chainElement, chainElement.getProperties(), usedProperties);

            findUsedPropertiesInScript(chainElement, usedProperties);
            findUsedPropertiesInMapper(chainElement, usedProperties);
            findUsedPropertiesInHeaderModification(chainElement, usedProperties);
        }

        return new ArrayList<>(usedProperties.values());
    }

    private void findUsedPropertiesInMapper(ChainElement element, Map<String, UsedProperty> usedProperties) {
        String elementType = element.getType();
        if (ELEMENTS_WITH_MAPPER.contains(elementType)) {
            Map<String, Object> elementProperties = element.getProperties();
            final List<Map<String, Object>> mappingDescription = new ArrayList<>();
            switch (elementType) {
                case CamelNames.MAPPER_2:
                    MapUtils.deepMapTraversalSafe(elementProperties,
                            value -> mappingDescription.add((Map<String, Object>) value), MAPPING_DESCRIPTION);
                    break;
                case CamelNames.SERVICE_CALL_COMPONENT:
                    MapUtils.deepMapTraversalSafe(elementProperties,
                            value -> mappingDescription.add((Map<String, Object>) value), CamelOptions.AFTER, MAPPING_DESCRIPTION);
                    MapUtils.deepMapTraversalSafe(elementProperties,
                            value -> mappingDescription.add((Map<String, Object>) value), CamelOptions.BEFORE, MAPPING_DESCRIPTION);
                    MapUtils.deepMapTraversalSafe(elementProperties,
                            value -> mappingDescription.add((Map<String, Object>) value), "handlerContainer", MAPPING_DESCRIPTION);
                    break;
                case CamelNames.HTTP_TRIGGER_COMPONENT:
                    MapUtils.deepMapTraversalSafe(elementProperties,
                            value -> mappingDescription.add((Map<String, Object>) value), "handlerContainer", MAPPING_DESCRIPTION);
                    break;
            }

            if (!mappingDescription.isEmpty()) {
                for (Map<String, Object> map : mappingDescription) {
                    MapUtils.deepMapTraversalSafe(map,
                            mapperTraversalCallback(usedProperties, buildUsedPropertyElement(element, UsedPropertyElementOperation.GET),
                                    UsedPropertySource.HEADER), "source", "headers");
                    MapUtils.deepMapTraversalSafe(map,
                            mapperTraversalCallback(usedProperties, buildUsedPropertyElement(element, UsedPropertyElementOperation.GET),
                                    UsedPropertySource.EXCHANGE_PROPERTY), "source", "properties");
                    MapUtils.deepMapTraversalSafe(map,
                            mapperTraversalCallback(usedProperties, buildUsedPropertyElement(element, UsedPropertyElementOperation.SET),
                                    UsedPropertySource.HEADER), "target", "headers");
                    MapUtils.deepMapTraversalSafe(map,
                            mapperTraversalCallback(usedProperties, buildUsedPropertyElement(element, UsedPropertyElementOperation.SET),
                                    UsedPropertySource.EXCHANGE_PROPERTY), "target", "properties");
                }
            }
        }
    }

    private static @NotNull UsedPropertyElement buildUsedPropertyElement(ChainElement element, UsedPropertyElementOperation operation) {
        UsedPropertyElement usedSrcElementHeader = UsedPropertyElement.builder()
                .id(element.getId())
                .name(element.getName())
                .type(element.getType())
                .build();
        usedSrcElementHeader.getOperations().add(operation);
        return usedSrcElementHeader;
    }

    @NotNull
    private Consumer<Object> mapperTraversalCallback(Map<String, UsedProperty> usedProperties,
                                                     UsedPropertyElement usedElement, UsedPropertySource usedPropertySource) {
        return headers -> {
            if (headers instanceof Collection<?> headersList) {
                addMapperProperty(usedProperties, headersList, usedElement, usedPropertySource);
            }
        };
    }

    private void addMapperProperty(Map<String, UsedProperty> usedProperties, Collection<?> headersList,
                                   UsedPropertyElement usedSrcElement, UsedPropertySource usedPropertySource) {
        for (Object entry : headersList) {
            if (entry instanceof Map map1) {
                String name = (String) map1.get("name");
                Map<String, Object> type = (Map<String, Object>) map1.get("type");
                String typeName = (String) type.get("name");
                if (StringUtils.isNotEmpty(name) && StringUtils.isNotEmpty(typeName)) {
                    AtomicReference<Map<String, Object>> attributeDataType = new AtomicReference<>(null);

                    if (!typeName.equals("array")) {
                        if (typeName.equals("object")) {
                            attributeDataType.set(type);
                        }
                        buildUsedProperty(usedProperties, name, usedPropertySource, UsedPropertyType.fromString(typeName),
                                false, attributeDataType.get(), usedSrcElement);
                    } else {
                        MapUtils.deepMapTraversalSafe(map1, (arrayType) -> {
                            if (arrayType.equals("object")) {
                                attributeDataType.set(type);
                            }
                            buildUsedProperty(usedProperties, name, usedPropertySource,
                                    UsedPropertyType.fromString((String) arrayType), true, attributeDataType.get(), usedSrcElement);
                        }, "type", "itemType", "name");
                    }
                }
            }
        }
    }

    private void findUsedPropertiesInHeaderModification(ChainElement element, Map<String, UsedProperty> usedProperties) {
        String elementType = element.getType();
        if (CamelNames.HEADER_MODIFICATION.equals(elementType)) {
            Map<String, Object> elementProperties = element.getProperties();

            UsedPropertyElement usedElement = buildUsedPropertyElement(element, UsedPropertyElementOperation.SET);

            Object headerModificationToAdd = elementProperties.getOrDefault("headerModificationToAdd", Collections.emptyMap());
            Object headerModificationToRemove = elementProperties.getOrDefault("headerModificationToRemove", Collections.emptyList());
            if (headerModificationToAdd instanceof Map map) {
                for (String key : ((Map<String, ?>) map).keySet()) {
                    buildUsedProperty(usedProperties, key, UsedPropertySource.HEADER, UsedPropertyType.UNKNOWN_TYPE, usedElement);
                }
            }
            if (headerModificationToRemove instanceof Collection collection) {
                for (String key : (Collection<String>) collection) {
                    buildUsedProperty(usedProperties, key, UsedPropertySource.HEADER, UsedPropertyType.UNKNOWN_TYPE, usedElement);
                }
            }
        }
    }

    private void findUsedPropertiesInScript(ChainElement element, Map<String, UsedProperty> usedProperties) {
        String elementType = element.getType();
        if (ELEMENTS_WITH_SCRIPT.contains(elementType)) {
            Map<String, Object> elementProperties = element.getProperties();
            StringBuilder scripts = new StringBuilder();
            switch (elementType) {
                case CamelNames.SCRIPT:
                    // properties.script
                    scripts.append(elementProperties.getOrDefault("script", ""));
                    break;
                case CamelNames.SERVICE_CALL_COMPONENT:
                    // properties.after[i].script (if properties.after[i].type == 'script')
                    // properties.before.script (if properties.before.type == 'script')
                    Object after = elementProperties.getOrDefault("after", Collections.emptyList());
                    if (after instanceof Collection afterCollection) {
                        for (Object afterObject : afterCollection) {
                            if (afterObject instanceof Map) {
                                Map<String, Object> map = (Map<String, Object>) afterObject;
                                scripts.append(map.getOrDefault("script", "")).append("\n");
                            }
                        }
                    }

                    Object before = elementProperties.get("before");
                    if (before instanceof Map beforeMap) {
                        scripts.append(beforeMap.getOrDefault("script", ""));
                    }
                    break;
                case CamelNames.HTTP_TRIGGER_COMPONENT:
                    // properties.handlerContainer.script
                    Object handlerContainer = elementProperties.get("handlerContainer");
                    if (handlerContainer instanceof Map containerMap) {
                        scripts.append(containerMap.getOrDefault("script", ""));
                    }
                    break;
            }

            if (!scripts.isEmpty()) {
                UsedPropertyElement usedElement = UsedPropertyElement.builder()
                        .id(element.getId())
                        .name(element.getName())
                        .type(element.getType())
                        .build();

                // get headers
                buildUsedProperties(scripts, UsedPropertyElementOperation.GET, usedElement, usedProperties,
                        GROOVY_GET_HEADERS_PATTERN, UsedPropertySource.HEADER, GROOVY_GET_HEADER_GROUPS);

                // set headers
                buildUsedProperties(scripts, UsedPropertyElementOperation.SET, usedElement, usedProperties,
                        GROOVY_SET_HEADERS_PATTERN, UsedPropertySource.HEADER, GROOVY_SET_HEADER_GROUPS);

                // get properties
                buildUsedProperties(scripts, UsedPropertyElementOperation.GET, usedElement, usedProperties,
                        GROOVY_GET_PROPERTIES_PATTERN, UsedPropertySource.EXCHANGE_PROPERTY, GROOVY_GET_PROPERTIES_GROUPS);

                // set properties
                buildUsedProperties(scripts, UsedPropertyElementOperation.SET, usedElement, usedProperties,
                        GROOVY_SET_PROPERTIES_PATTERN, UsedPropertySource.EXCHANGE_PROPERTY, GROOVY_SET_PROPERTIES_GROUPS);
            }
        }
    }

    private void buildUsedProperties(StringBuilder scripts, UsedPropertyElementOperation operation, UsedPropertyElement usedElement,
                                     Map<String, UsedProperty> usedProperties,
                                     Pattern pattern, UsedPropertySource usedPropertySource, int[] matchGroups) {
        Matcher groovyGetHeadersMatcher = pattern.matcher(scripts);
        while (groovyGetHeadersMatcher.find()) {
            for (int group : matchGroups) {
                String propertyName = groovyGetHeadersMatcher.group(group);
                if (propertyName != null) {
                    usedElement.getOperations().add(operation);
                    buildUsedProperty(usedProperties, propertyName, usedPropertySource, UsedPropertyType.UNKNOWN_TYPE, usedElement);
                    break;
                }
            }
        }
    }

    private void findUsedProperties(ChainElement element, Map<String, Object> properties, Map<String, UsedProperty> usedProperties) {
        String elementType = element.getType();

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // skip mapper property
            if (EXCLUDE_MAPPER_ELEMENTS.contains(elementType) && MAPPING_DESCRIPTION.equals(key)) {
                continue;
            }

            parseProperty(element, usedProperties, value, elementType);
        }
    }

    private void findUsedProperties(ChainElement element, Collection<Object> properties, Map<String, UsedProperty> usedProperties) {
        String elementType = element.getType();

        for (Object property : properties) {
            parseProperty(element, usedProperties, property, elementType);
        }
    }

    private void parseProperty(ChainElement element, Map<String, UsedProperty> usedProperties, Object value, String elementType) {
        if (value instanceof Collection listValue) {
            findUsedProperties(element, listValue, usedProperties);
        }
        if (value instanceof Map mapValue) {
            findUsedProperties(element, mapValue, usedProperties);
        }
        if (value instanceof String stringValue) {
            Matcher exPropertiesMatcher = PROPS_SIMPLE_PATTERN.matcher(stringValue);
            Matcher exHeadersMatcher = HEADERS_SIMPLE_PATTERN.matcher(stringValue);

            UsedPropertyElement usedElement = UsedPropertyElement.builder()
                    .id(element.getId())
                    .name(element.getName())
                    .type(elementType)
                    .build();

            usedElement.getOperations().add(UsedPropertyElementOperation.GET); // constant operation for simple lang

            findUsedPropertyMatches(usedProperties, exPropertiesMatcher, usedElement, UsedPropertySource.EXCHANGE_PROPERTY, EX_PROP_GROUPS);
            findUsedPropertyMatches(usedProperties, exHeadersMatcher, usedElement, UsedPropertySource.HEADER, EX_HEADER_GROUPS);
        }
    }

    private void findUsedPropertyMatches(Map<String, UsedProperty> usedProperties, Matcher exPropertiesMatcher, UsedPropertyElement usedElement, UsedPropertySource usedPropertySource, int[] groups) {
        while (exPropertiesMatcher.find()) {
            for (int group : groups) {
                String propertyName = exPropertiesMatcher.group(group);
                if (propertyName != null) {
                    buildUsedProperty(usedProperties, propertyName, usedPropertySource, UsedPropertyType.UNKNOWN_TYPE, usedElement);
                    break;
                }
            }
        }
    }

    private void buildUsedProperty(Map<String, UsedProperty> usedProperties, String propName,
                                   UsedPropertySource source, UsedPropertyType type, UsedPropertyElement usedElement) {
        buildUsedProperty(usedProperties, propName, source, type, false, null, usedElement);
    }

    private void buildUsedProperty(Map<String, UsedProperty> usedProperties, String propName,
                                   UsedPropertySource source, UsedPropertyType type,
                                   boolean isArray, Map<String, Object> attributeDataType, UsedPropertyElement usedElement) {
        UsedProperty usedProperty = usedProperties.computeIfAbsent(
                buildUsedPropertyKey(propName, source),
                key ->
                        UsedProperty.builder()
                                .name(propName)
                                .source(source)
                                .type(type)
                                .isArray(isArray)
                                .attributeDataType(attributeDataType)
                                .build());

        Map<String, UsedPropertyElement> relatedElements = usedProperty.getRelatedElements();
        UsedPropertyElement existingRelatedElement = relatedElements.get(usedElement.getId());
        if (existingRelatedElement != null) {
            existingRelatedElement.merge(usedElement);
        } else {
            relatedElements.put(usedElement.getId(), usedElement);
        }
    }

    private static String buildUsedPropertyKey(String name, UsedPropertySource source) {
        return name + source.toString();
    }
}
