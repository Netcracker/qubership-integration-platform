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

package org.qubership.integration.platform.runtime.catalog.mapper.atlasmap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atlasmap.json.v2.JsonDataSource;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.v2.*;
import io.atlasmap.v2.Constant;
import io.atlasmap.v2.Properties;
import io.atlasmap.xml.v2.XmlDataSource;
import io.atlasmap.xml.v2.XmlField;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.qubership.integration.platform.mapper.ComplexField;
import org.qubership.integration.platform.mapper.GeneratedField;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.mapper.*;
import org.qubership.integration.platform.runtime.catalog.mapper.atlasmap.xml.XmlTemplateBuilder;
import org.qubership.integration.platform.runtime.catalog.mapper.expressions.FieldKind;
import org.qubership.integration.platform.runtime.catalog.mapper.expressions.ToAtlasMapExpressionConverter;
import org.qubership.integration.platform.runtime.catalog.mapper.metadata.DataFormat;
import org.qubership.integration.platform.runtime.catalog.mapper.metadata.MetadataUtils;
import org.qubership.integration.platform.runtime.catalog.model.mapper.atlasmap.action.QIPDefaultValueAction;
import org.qubership.integration.platform.runtime.catalog.model.mapper.atlasmap.action.QIPDictionaryAction;
import org.qubership.integration.platform.runtime.catalog.model.mapper.atlasmap.action.QIPFormatDateTimeAction;
import org.qubership.integration.platform.runtime.catalog.model.mapper.atlasmap.characteristic.PropertyCharacteristics;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.action.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Element;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.MessageSchema;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.ObjectSchema;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

@Slf4j
@Service
@MappingInterpretation("AtlasMap")
public class AtlasMapInterpreter implements MappingInterpreter {
    private static final String MAPPING_NAME = "QIP_Mapping_Configuration";
    private static final String SERIALIZE_CONFIGURATION_ERROR_MESSAGE = "Unable to serialize Atlas Map configuration.";
    private static final String MAPPING_CREATION_ERROR_MESSAGE = "Unable to create mapping for action: ";
    private static final String EMPTY_FIELDS_ERROR_MESSAGE = "Cannot define input or output fields.";
    private static final String INCORRECT_DATA_SOURCE_FORMAT_ERROR_MESSAGE = "Incorrect Data Source Format For ";
    private static final String UNABLE_TO_FIND_DEFINITION_ERROR_MESSAGE = "Unable to find definition with id = ";
    private static final String UNABLE_TO_FIND_ATTRIBUTE_ERROR_MESSAGE = "Unable to find attribute with id = ";
    private static final String UNABLE_TO_FIND_PROPERTY_ERROR_MESSAGE = "Unable to find property with id = ";
    private static final String UNABLE_TO_FIND_HEADER_ERROR_MESSAGE = "Unable to find header with id = ";
    private static final String UNABLE_TO_FIND_CONSTANT_ERROR_MESSAGE = "Unable to find constant with id = ";
    private static final String UNABLE_TO_DEFINE_DATA_FORMAT_ERROR_MESSAGE = "Unable to define data format.";
    private static final String UNABLE_TO_INTERPRETER_EXPRESSION = "Unable to interpreter expression: ";

    private static final String FAILED_TO_RESOLVE_FIELD_REFERENCE = "Failed to resolve field reference: ";
    private static final String COMBINE_ARRAY_AND_PRIMITIVE_INTO_ARRAY_ERROR_MESSAGE = "Can not combine array and primitive field into array.";
    private static final String COMBINE_SEVERAL_ARRAYS_ERROR_MESSAGE = "Can not combine several arrays.";
    private static final String MULTIPLE_FIELDS_AGGREGATION_ERROR_MESSAGE = "Transformation is mandatory for multiple fields aggregation.";
    private static final String UNKNOWN_TRANSFORMATION_ERROR_MESSAGE = "Unknown transformation: %s";
    private static final String UNKNOWN_CONSTANT_VALUE_GENERATOR_ERROR_MESSAGE = "Unknown constant value generator: %s";
    private static final String ATTRIBUTE_REFERENCE_PATH_IS_EMPTY_ERROR_MESSAGE = "Attribute reference path is empty.";

    private static final String URI_ATLAS_PREFIX = "atlas:cip";
    private static final String CURRENT_SCOPE = "current";
    private static final String CURRENT_PATH = "/current/";
    private static final String EXCHANGE_SCOPE = "camelExchangeProperty";
    private static final String EXCHANGE_PATH = "/camelExchangeProperty/";
    private static final String TARGET_DOC_ID = "target";
    private static final String SOURCE_DOC_ID = "source";
    private static final String CONSTANT_DOC_ID = "DOC.Properties.1";
    private static final String PROPERTIES_DOC_ID = "DOC.Properties.2";

    private static final Pattern XML_TEXT_PATH_PATTERN = Pattern.compile("\\/#text$");

    private final ObjectMapper objectMapper;

    private final DataTypeToFieldTypeConverter dataTypeToFieldTypeConverter;

    @Autowired
    public AtlasMapInterpreter(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.dataTypeToFieldTypeConverter = new DataTypeToFieldTypeConverter();
    }

    @Override
    public String getInterpretation(MappingDescription mappingDescription) {
        AtlasMapping atlasMapping = new AtlasMapping();
        atlasMapping.setName(MAPPING_NAME);

        //Define data sources
        fillDataSources(mappingDescription, atlasMapping);

        //Define constants
        fillConstants(mappingDescription, atlasMapping);

        //Define properties
        fillProperties(mappingDescription, atlasMapping);

        //Define mapping relations
        fillActions(mappingDescription, atlasMapping);

        try {
            return objectMapper.writeValueAsString(atlasMapping);
        } catch (JsonProcessingException e) {
            throw new SnapshotCreationException(SERIALIZE_CONFIGURATION_ERROR_MESSAGE);
        }
    }

    private void fillDataSources(MappingDescription mappingDescription, AtlasMapping atlasMapping) {
        //Setting up default data sources
        if (mappingDescription.getSource().getBody().getKind() != TypeKind.NULL) {
            atlasMapping.getDataSource().add(
                    getDataSource(
                            DataSourceType.SOURCE.value().toLowerCase(),
                            mappingDescription.getSource(),
                            DataSourceType.SOURCE,
                            Collections.emptyMap()
                    )
            );
        }
        if (mappingDescription.getTarget().getBody().getKind() != TypeKind.NULL) {
            atlasMapping.getDataSource().add(
                    getDataSource(
                            DataSourceType.TARGET.value().toLowerCase(),
                            mappingDescription.getTarget(),
                            DataSourceType.TARGET,
                            Collections.emptyMap()
                    )
            );
        }

        //Setting up complex properties as additional data sources
        fillAdditionalDataSources(mappingDescription, atlasMapping);
    }

    private void fillAdditionalDataSources(MappingDescription mappingDescription, AtlasMapping atlasMapping) {
        if (!mappingDescription.getSource().getProperties().isEmpty()) {
            mappingDescription
                    .getSource()
                    .getProperties()
                    .stream()
                    .filter(property -> property.getType() instanceof ComplexType)
                    .forEach(property -> fillPropertyDataSources(property, DataSourceType.SOURCE, atlasMapping));
        }

        if (!mappingDescription.getTarget().getProperties().isEmpty()) {
            mappingDescription
                    .getTarget()
                    .getProperties()
                    .stream()
                    .filter(property -> property.getType() instanceof ComplexType)
                    .forEach(property -> fillPropertyDataSources(property, DataSourceType.TARGET, atlasMapping));
        }

        mappingDescription.getConstants().stream()
                .filter(constant -> constant.getValueSupplier().getKind().equals(SupplierKind.GENERATED))
                .map(this::createDataSourceForConstantWithGeneratedValue)
                .forEach(atlasMapping.getDataSource()::add);
    }

    private DataSource createDataSourceForConstantWithGeneratedValue(
            org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant constant
    ) {
        DataSource dataSource = new DataSource();
        dataSource.setDataSourceType(DataSourceType.SOURCE);
        dataSource.setId(constant.getId());
        dataSource.setName(constant.getName() + "-" + constant.getId());
        ValueGenerator generator = ((GeneratedValue) constant.getValueSupplier()).getGenerator();
        dataSource.setUri(buildGeneratedValueUri(constant.getId(), generator));
        return dataSource;
    }

    private String buildGeneratedValueUri(String id, ValueGenerator generator) {
        List<NameValuePair> queryParameters = new ArrayList<>();
        queryParameters.add(new BasicNameValuePair("name", generator.getName()));
        generator.getParameters().stream()
                .map(value -> new BasicNameValuePair("parameter", value))
                .forEachOrdered(queryParameters::add);
        return URI_ATLAS_PREFIX + ":generated:" + id + "?" + URLEncodedUtils.format(queryParameters, StandardCharsets.UTF_8);
    }

    private void fillPropertyDataSources(Attribute property, DataSourceType direction, AtlasMapping atlasMapping) {
        DataType body = switch (property.getType().getKind()) {
            case OBJECT -> property.getType();
            case ARRAY -> ((ArrayType) property.getType()).getItemType();
            default -> new NullType(null);
        };
        MessageSchema propertyDataSourceSchema = new MessageSchema(null, null, body, null);
        if (propertyDataSourceSchema.getBody().getKind() != TypeKind.NULL) {
            Map<String, String> parameters = DataSourceType.TARGET.equals(direction)
                    ? Collections.singletonMap("serializeTargetDocument", "false")
                    : Collections.emptyMap();
            atlasMapping.getDataSource().add(getDataSource(property.getName(), propertyDataSourceSchema, direction, parameters));
        }
    }

    private DataSource getDataSource(String dataSourceName, MessageSchema dataSourceSchema, DataSourceType dataSourceType, Map<String, String> parameters) {
        DataSource dataSource;
        DataFormat dataFormat = DataFormat.JSON;
        if (dataSourceSchema.getBody() instanceof ComplexType body) {
            dataFormat = MetadataUtils.getDataFormat(body.getMetadata());
        }
        switch (dataFormat) {
            case UNSPECIFIED, JSON -> dataSource = new JsonDataSource();
            case XML -> {
                XmlDataSource xmlDataSource = new XmlDataSource();
                XmlTemplateBuilder xmlTemplateBuilder = new XmlTemplateBuilder();
                try {
                    String templateText = xmlTemplateBuilder.setType(dataSourceSchema.getBody()).build();
                    xmlDataSource.setTemplate(templateText);
                } catch (Exception exception) {
                    throw new SnapshotCreationException("Failed to create XML document template", null, exception);
                }
                dataSource = xmlDataSource;

            }
            default -> throw new SnapshotCreationException(INCORRECT_DATA_SOURCE_FORMAT_ERROR_MESSAGE.concat(dataSourceType.value()));
        }

        dataSource.setId(dataSourceName);
        dataSource.setName(dataSourceName);
        dataSource.setDescription(dataSourceName);
        dataSource.setUri(buildDataSourceUri(dataSourceName, dataFormat, parameters));
        dataSource.setDataSourceType(dataSourceType);

        return dataSource;
    }

    private static String buildDataSourceUri(String name, DataFormat format, Map<String, String> parameters) {
        StringBuilder sb = new StringBuilder();
        sb
                .append(URI_ATLAS_PREFIX)
                .append(":")
                .append((DataFormat.UNSPECIFIED.equals(format) ? DataFormat.JSON : format)
                        .name().toLowerCase(Locale.ROOT))
                .append(":")
                .append(name);
        if (!parameters.isEmpty()) {
            sb.append("?");
            sb.append(parameters.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("&")));
        }
        return sb.toString();
    }

    private void fillConstants(MappingDescription mappingDescription, AtlasMapping atlasMapping) {
        List<Constant> constantList = mappingDescription
                .getConstants()
                .stream()
                .filter(constant -> constant.getValueSupplier().getKind().equals(SupplierKind.GIVEN))
                .map(this::getConstant)
                .toList();

        Constants constants = new Constants();
        constants.getConstant().addAll(constantList);
        atlasMapping.setConstants(constants);
    }

    private Constant getConstant(org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant constant) {
        Constant atlasMapConstant = new Constant();
        atlasMapConstant.setName(constant.getName());
        atlasMapConstant.setFieldType(dataTypeToFieldTypeConverter.convert(constant.getType()));
        atlasMapConstant.setValue(getConstantValue(constant));
        return atlasMapConstant;
    }

    private String getConstantValue(org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant constant) {
        ValueSupplier valueSupplier = constant.getValueSupplier();
        return SupplierKind.GIVEN.equals(valueSupplier.getKind())
                ? ((GivenValue) valueSupplier).getValue() : constant.getName();
    }

    private void fillProperties(MappingDescription mappingDescription, AtlasMapping atlasMapping) {
        Properties properties = new Properties();
        atlasMapping.setProperties(properties);

        atlasMapping
                .getProperties()
                .getProperty()
                .addAll(getProperties(mappingDescription.getSource().getHeaders(), CURRENT_SCOPE, DataSourceType.SOURCE));

        atlasMapping
                .getProperties()
                .getProperty()
                .addAll(getProperties(mappingDescription.getSource().getProperties(), EXCHANGE_SCOPE, DataSourceType.SOURCE));

        atlasMapping
                .getProperties()
                .getProperty()
                .addAll(getProperties(mappingDescription.getTarget().getHeaders(), CURRENT_SCOPE, DataSourceType.TARGET));

        atlasMapping
                .getProperties()
                .getProperty()
                .addAll(getProperties(mappingDescription.getTarget().getProperties(), EXCHANGE_SCOPE, DataSourceType.TARGET));
    }

    private List<Property> getProperties(Collection<Attribute> properties, String scope, DataSourceType dataSourceType) {
        return properties
                .stream()
                .filter(attribute -> !(attribute.getType() instanceof ComplexType))
                .map(attribute -> getProperty(attribute, scope, dataSourceType))
                .collect(Collectors.toList());

    }

    private Property getProperty(Attribute attribute, String scope, DataSourceType dataSourceType) {
        Property atlasMapProperty = new Property();
        atlasMapProperty.setName(attribute.getName());
        atlasMapProperty.setFieldType(dataTypeToFieldTypeConverter.convert(attribute.getType()));
        atlasMapProperty.setScope(scope);
        atlasMapProperty.setDataSourceType(dataSourceType);
        atlasMapProperty.setValue(attribute.getDefaultValue());

        return atlasMapProperty;
    }

    private void fillActions(MappingDescription mappingDescription, AtlasMapping atlasMapping) {
        var sourceElementMap = buildElementMap(mappingDescription.getSource(), mappingDescription.getConstants());
        var targetElementMap = buildElementMap(mappingDescription.getTarget(), Collections.emptyList());

        List<? extends BaseMapping> mappings = mappingDescription
                .getActions()
                .stream()
                // Complex objects mappings first
                .sorted(Comparator.comparing(action -> action.getTarget().getPath().size()))
                .flatMap(action ->
                        getMappings(mappingDescription, action, sourceElementMap, targetElementMap)
                                .stream()
                                .map(mapping -> processTransformation(
                                        mappingDescription, mapping, action, sourceElementMap, targetElementMap))
                )
                .toList();

        atlasMapping.setMappings(new Mappings());
        atlasMapping.getMappings().getMapping().addAll(mappings);
    }

    private Map<String, ElementMapBuilder.ElementWithContext> buildElementMap(
            MessageSchema messageSchema,
            Collection<org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant> constants
    ) {
        ElementMapBuilder elementMapBuilder = new ElementMapBuilder();
        Map<String, ElementMapBuilder.ElementWithContext> result = new HashMap<>();
        Stream.of(messageSchema.getHeaders(), messageSchema.getProperties())
                .map(attributes -> new ObjectType(new ObjectSchema("", List.copyOf(attributes), null), null, null))
                .map(elementMapBuilder::buildElementMap)
                .forEach(result::putAll);
        result.putAll(elementMapBuilder.buildElementMap(messageSchema.getBody()));
        constants.forEach(constant -> result.put(constant.getId(), new ElementMapBuilder.ElementWithContext(
                constant, new ElementMapBuilder.ElementContext(Collections.emptyMap()))));
        return result;
    }

    private List<? extends BaseMapping> getMappings(
            MappingDescription mappingDescription,
            MappingAction action,
            Map<String, ElementMapBuilder.ElementWithContext> sourceElementMap,
            Map<String, ElementMapBuilder.ElementWithContext> targetElementMap
    ) {
        List<BaseMapping> result = new ArrayList<>();

        List<Field> inputFields = action
                .getSources()
                .stream()
                .map(source -> buildAtlasField(
                        source,
                        mappingDescription.getSource(),
                        sourceElementMap,
                        true
                ))
                .toList();

        Field outputField = buildAtlasField(
                action.getTarget(),
                mappingDescription.getTarget(),
                targetElementMap,
                false
        );

        validateForSupportedMappings(inputFields, outputField, action);

        Mapping mapping = new Mapping();
        mapping.setId("mapping.".concat(UUID.randomUUID().toString()));
        mapping.getOutputField().add(outputField);

        boolean hasExpressionTransformation = Optional.ofNullable(action.getTransformation())
                .map(transformation -> transformation.getName().equals(TransformationType.EXPRESSION.getValue())
                        || transformation.getName().equals(TransformationType.CONDITIONAL.getValue()))
                .orElse(false);

        // Simple one to one case
        if (inputFields.size() == 1 && !hasExpressionTransformation) {
            mapping.getInputField().add(inputFields.get(0));
            result.add(mapping);
            return result;
        }

        // Many primitive to one array case
        if (outputField.getCollectionType().equals(CollectionType.ARRAY)
                && !(outputField instanceof ConstantField || outputField instanceof PropertyField)
                && inputFields.stream().allMatch(field -> field.getCollectionType().equals(CollectionType.NONE))
                && !hasExpressionTransformation
        ) {
            final AtomicInteger arrayIndexCounter = new AtomicInteger(0);
            List<Mapping> mappings = inputFields
                    .stream()
                    .map(inputField -> {
                        int index = arrayIndexCounter.getAndIncrement();

                        Mapping map = new Mapping();
                        inputField.setIndex(0);
                        map.getInputField().add(inputField);

                        Field outputFieldCopy = buildAtlasField(
                                action.getTarget(),
                                mappingDescription.getTarget(),
                                targetElementMap,
                                false
                        );
                        String indexedPath = getIndexedPath(outputFieldCopy.getPath(), index);
                        replacePathPrefix(outputFieldCopy, outputFieldCopy.getPath(), indexedPath, false);
                        map.getOutputField().add(outputFieldCopy);

                        return map;
                    })
                    .toList();
            return mappings;
        }

        //Many to one case
        FieldGroup inputFieldGroup = new FieldGroup();
        inputFieldGroup.getField().addAll(inputFields);
        inputFieldGroup.setActions(new ArrayList<>());
        mapping.setInputFieldGroup(inputFieldGroup);

        result.add(mapping);
        return result;
    }

    private Field buildAtlasField(
            ElementReference elementReference,
            MessageSchema messageSchema,
            Map<String, ElementMapBuilder.ElementWithContext> elementMap,
            boolean isSource
    ) {
        return switch (elementReference.getType()) {
            case CONSTANT -> buildConstantField(elementReference, elementMap);
            case ATTRIBUTE -> {
                AttributeReference attributeReference = (AttributeReference) elementReference;
                yield switch (attributeReference.getKind()) {
                    case HEADER -> buildHeaderField(attributeReference, elementMap);
                    case PROPERTY -> buildPropertyField(attributeReference, elementMap);
                    case BODY -> buildBodyField(attributeReference, messageSchema, isSource, elementMap);
                };
            }
        };
    }

    private Field buildHeaderField(
            AttributeReference attributeReference,
            Map<String, ElementMapBuilder.ElementWithContext> elementMap
    ) {
        String headerId = attributeReference.getPath().stream().findFirst().orElseThrow(
                () -> new SnapshotCreationException(ATTRIBUTE_REFERENCE_PATH_IS_EMPTY_ERROR_MESSAGE));
        var attributeWithContext = elementMap.get(headerId);
        if (isNull(attributeWithContext)) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE
                    .concat(UNABLE_TO_FIND_HEADER_ERROR_MESSAGE).concat(headerId));
        }
        Attribute header = (Attribute) attributeWithContext.element();
        return buildCommonPropertyField(header, new PropertyCharacteristics(CURRENT_SCOPE, CURRENT_PATH, PROPERTIES_DOC_ID));
    }

    private Field buildPropertyField(
            AttributeReference attributeReference,
            Map<String, ElementMapBuilder.ElementWithContext> elementMap
    ) {
        List<String> path = attributeReference.getPath();
        String id = path.stream().findFirst().orElseThrow(
                () -> new SnapshotCreationException(ATTRIBUTE_REFERENCE_PATH_IS_EMPTY_ERROR_MESSAGE));
        var attributeWithContext = elementMap.get(id);
        if (isNull(attributeWithContext)) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE
                    .concat(UNABLE_TO_FIND_PROPERTY_ERROR_MESSAGE).concat(id));
        }
        Attribute root = (Attribute) attributeWithContext.element();
        DataType rootType = DataTypeUtils.resolveType(root.getType(), Collections.emptyMap()).type();
        if (rootType instanceof ComplexType) {
            if (path.size() == 1) {
                Field field = buildAttributeField(attributeReference, DataFormat.JSON, root.getName(), elementMap, false);
                String newPrefix = rootType.getKind().equals(TypeKind.ARRAY) ? "<>/" : "/";
                replacePathPrefix(field, field.getPath(), newPrefix, true);
                return field;
            } else {
                boolean rootIsArray = rootType.getKind().equals(TypeKind.ARRAY);
                return buildAttributeField(
                        new AttributeReference(
                                attributeReference.getKind(),
                                attributeReference.getPath().subList(1, attributeReference.getPath().size()),
                                attributeReference.getMetadata()
                        ), DataFormat.JSON, root.getName(), elementMap, rootIsArray);
            }
        } else if (path.size() == 1) {
            return buildCommonPropertyField(root, new PropertyCharacteristics(EXCHANGE_SCOPE, EXCHANGE_PATH, PROPERTIES_DOC_ID));
        } else {
            throw new SnapshotCreationException("Path length for scalar attribute is greater than 1");
        }
    }

    private void replacePathPrefix(Field field, String oldPrefix, String newPrefix, boolean replaceExtraCharacter) {
        if (field.getPath().startsWith(oldPrefix)) {
            int index = replaceExtraCharacter
                    ? Math.min(field.getPath().length(), oldPrefix.length() + 1)
                    : oldPrefix.length();
            field.setPath(newPrefix + field.getPath().substring(index));
        }
        if (field instanceof ComplexField) {
            ((ComplexField) field).getChildFields().forEach(f -> replacePathPrefix(f, oldPrefix, newPrefix, replaceExtraCharacter));
        }
    }

    private Field buildBodyField(
            AttributeReference attributeReference,
            MessageSchema messageSchema,
            boolean isSource,
            Map<String, ElementMapBuilder.ElementWithContext> elementMap
    ) {
        DataFormat dataFormat = MetadataUtils.getDataFormat(messageSchema.getBody().getMetadata());
        String documentId = isSource ? SOURCE_DOC_ID : TARGET_DOC_ID;
        DataTypeUtils.ResolveResult bodyTypeResolveResult =
                DataTypeUtils.resolveType(messageSchema.getBody(), Collections.emptyMap());
        DataType rootType = (
                (bodyTypeResolveResult.type() instanceof CompoundType compoundType)
                        && !attributeReference.getPath().isEmpty()
        )
                ? DataTypeUtils.findBranchByAttributeId(compoundType, attributeReference.getPath().get(0),
                bodyTypeResolveResult.definitionMap()).orElse(bodyTypeResolveResult.type())
                : bodyTypeResolveResult.type();
        boolean rootIsArray = rootType.getKind().equals(TypeKind.ARRAY);
        return buildAttributeField(attributeReference, dataFormat, documentId, elementMap, rootIsArray);
    }

    private Field buildAttributeField(
            AttributeReference attributeReference,
            DataFormat dataFormat,
            String documentId,
            Map<String, ElementMapBuilder.ElementWithContext> elementMap,
            boolean rootIsArray
    ) {
        List<Element> path = resolveElementsForPath(attributeReference.getPath(), elementMap);

        if (path.isEmpty()) {
            throw new SnapshotCreationException(ATTRIBUTE_REFERENCE_PATH_IS_EMPTY_ERROR_MESSAGE);
        }

        Attribute lastAttribute = (Attribute) path.get(path.size() - 1);

        String fieldName = lastAttribute.getName();
        String fieldPath = buildAtlasMapFieldPath(path, elementMap);
        if (rootIsArray) {
            fieldPath = "<>" + fieldPath;
        }

        Map<String, TypeDefinition> definitionMap = elementMap.get(lastAttribute.getId()).context().definitionMap();
        DataTypeUtils.ResolveResult typeResolveResult = DataTypeUtils.resolveType(lastAttribute.getType(), definitionMap);

        DataType dataType = typeResolveResult.type();
        boolean isArray = dataType.getKind().equals(TypeKind.ARRAY);
        if (isArray) {
            DataTypeUtils.ResolveResult itemTypeResolveResult = DataTypeUtils.resolveType(
                    ((ArrayType) dataType).getItemType(), typeResolveResult.definitionMap());
            dataType = itemTypeResolveResult.type();
        }

        Field field;
        if (dataType instanceof ArrayType) {
            field = new ComplexField();
            field.setName(fieldName);
            field.setPath(fieldPath);
            field.setFieldType(FieldType.ANY);
        }
        if (dataType instanceof CompoundType) {
            // TODO merge fields of nesting types
            field = new ComplexField();
            field.setName(fieldName);
            field.setPath(fieldPath);
            field.setFieldType(FieldType.ANY);
        } else if (dataType instanceof ObjectType objectType) {
            List<Field> childFields = objectType.getSchema().getAttributes().stream()
                    .map(attribute -> new AttributeReference(
                            attributeReference.getKind(),
                            ListUtils.union(attributeReference.getPath(), Collections.singletonList(attribute.getId())),
                            null))
                    .map(reference -> buildAttributeField(reference, dataFormat, documentId, elementMap, rootIsArray))
                    .toList();
            field = new ComplexField(childFields);
            field.setName(fieldName);
            field.setPath(fieldPath);
            field.setFieldType(FieldType.ANY);
        } else {
            switch (dataFormat) {
                case UNSPECIFIED, JSON -> {
                    field = new JsonField();
                    field.setName(fieldName);
                    field.setPath(fieldPath);
                }
                case XML -> {
                    boolean isXmlAttribute = fieldName.startsWith("@");

                    Matcher pathMatcher = XML_TEXT_PATH_PATTERN.matcher(fieldPath);
                    if (pathMatcher.find()) {
                        fieldName = path.get(path.size() - 2).getName();
                        fieldPath = fieldPath.replaceAll(XML_TEXT_PATH_PATTERN.pattern(), "");
                    }

                    field = new XmlField();
                    ((XmlField) field).setAttribute(isXmlAttribute);
                    field.setName(fieldName);
                    field.setPath(fieldPath);
                }
                default -> throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE
                        .concat(UNABLE_TO_DEFINE_DATA_FORMAT_ERROR_MESSAGE));
            }
            field.setFieldType(dataTypeToFieldTypeConverter.convert(dataType, definitionMap));
        }

        field.setValue(ValueExtractor.getValue(dataType.getKind(), lastAttribute.getDefaultValue()));
        field.setDocId(documentId);
        field.setCollectionType(isArray ? CollectionType.ARRAY : CollectionType.NONE);

        return field;
    }

    private List<Element> resolveElementsForPath(
            List<String> path,
            Map<String, ElementMapBuilder.ElementWithContext> elementMap
    ) {
        return path.stream()
                .map(
                        id -> Optional.ofNullable(elementMap.get(id))
                                .map(ElementMapBuilder.ElementWithContext::element)
                                .orElseThrow(() ->
                                        new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE
                                                .concat(UNABLE_TO_FIND_ATTRIBUTE_ERROR_MESSAGE).concat(id)))
                ).toList();
    }

    private String buildAtlasMapFieldPath(
            List<Element> path,
            Map<String, ElementMapBuilder.ElementWithContext> attributeMap
    ) {
        return path.stream()
                .map(attribute -> {
                    var defaultAttributeContextInfo = new ElementMapBuilder.ElementWithContext(
                            attribute, new ElementMapBuilder.ElementContext(Collections.emptyMap()));
                    var result = DataTypeUtils.resolveType(
                            attribute.getType(),
                            attributeMap.getOrDefault(attribute.getId(), defaultAttributeContextInfo)
                                    .context().definitionMap()
                    );
                    return result.type().getKind().equals(TypeKind.ARRAY)
                            ? attribute.getName().concat("<>")
                            : attribute.getName();
                })
                .collect(Collectors.joining("/", "/", ""));
    }

    private Field buildConstantField(
            ElementReference elementReference,
            Map<String, ElementMapBuilder.ElementWithContext> attributeMap
    ) {
        ConstantReference constantReference = (ConstantReference) elementReference;
        String constantId = constantReference.getConstantId();
        var attributeWithContext = attributeMap.get(constantId);
        if (isNull(attributeWithContext)) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE
                    .concat(UNABLE_TO_FIND_CONSTANT_ERROR_MESSAGE).concat(constantId));
        }
        org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant constant =
                (org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.Constant)
                        attributeWithContext.element();

        if (constant.getValueSupplier().getKind().equals(SupplierKind.GENERATED)) {
            GeneratedField field = new GeneratedField();
            field.setFieldType(dataTypeToFieldTypeConverter.convert(constant.getType()));
            field.setPath("/".concat(constant.getName()));
            field.setCollectionType(CollectionType.NONE);
            field.setDocId(constant.getId());
            return field;
        } else {
            ConstantField field = new ConstantField();
            field.setFieldType(dataTypeToFieldTypeConverter.convert(constant.getType()));
            field.setValue(((GivenValue) constant.getValueSupplier()).getValue());
            field.setPath("/".concat(constant.getName()));
            field.setCollectionType(CollectionType.NONE);
            field.setDocId(CONSTANT_DOC_ID);
            return field;
        }
    }

    private PropertyField buildCommonPropertyField(Attribute propertyAttribute, PropertyCharacteristics propertyCharacteristics) {
        PropertyField propertyField = new PropertyField();

        propertyField.setDocId(propertyCharacteristics.docId());
        propertyField.setName(propertyAttribute.getName());
        propertyField.setScope(propertyCharacteristics.scope());
        propertyField.setPath(propertyCharacteristics.path().concat(propertyAttribute.getName()));
        propertyField.setFieldType(dataTypeToFieldTypeConverter.convert(propertyAttribute.getType()));
        propertyField.setCollectionType(CollectionType.NONE);

        return propertyField;
    }

    private String getIndexedPath(String path, int index) {
        String result = path;
        result = result.replace("<>", "<0>");
        String postfix = result.substring(result.lastIndexOf("<0>") + 2);
        return result.substring(0, result.lastIndexOf("<0>") + 1).concat(String.valueOf(index)).concat(postfix);
    }

    private BaseMapping processTransformation(
            MappingDescription mappingDescription,
            BaseMapping mapping,
            MappingAction action,
            Map<String, ElementMapBuilder.ElementWithContext> sourceElementMap,
            Map<String, ElementMapBuilder.ElementWithContext> targetElementMap
    ) {
        Transformation transformation = action.getTransformation();
        if (transformation == null) {
            return mapping;
        }

        Mapping processedMapping = null;
        if (mapping instanceof Mapping) {
            processedMapping = (Mapping) mapping;
            ArrayList<Action> actions = new ArrayList<>();

            TransformationType transformationType = TransformationType.fromValue(transformation.getName())
                    .orElseThrow(() -> new SnapshotCreationException(
                            String.format(UNKNOWN_TRANSFORMATION_ERROR_MESSAGE, transformation.getName())));
            switch (transformationType) {
                case DEFAULT_VALUE -> {
                    String defaultValue = transformation.getParameters().get(0);
                    QIPDefaultValueAction qipDefaultValueAction = new QIPDefaultValueAction(defaultValue);
                    actions.add(qipDefaultValueAction);
                }
                case FORMAT_DATE_TIME -> {
                    QIPFormatDateTimeAction qipFormatDateTimeAction = new QIPFormatDateTimeAction(
                            Boolean.valueOf(transformation.getParameters().get(0)),
                            transformation.getParameters().get(1),
                            transformation.getParameters().get(2),
                            transformation.getParameters().get(3),
                            Boolean.valueOf(transformation.getParameters().get(4)),
                            transformation.getParameters().get(5),
                            transformation.getParameters().get(6),
                            transformation.getParameters().get(7)
                    );
                    actions.add(qipFormatDateTimeAction);
                }
                case DICTIONARY -> {
                    List<String> params = transformation.getParameters();
                    String defaultValue = params.get(0);
                    Map<String, String> lookupTable = transformation.getParameters()
                            .stream()
                            .skip(1)
                            .map(DictionaryEntryParser::parse)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    QIPDictionaryAction qipDictionaryAction = new QIPDictionaryAction(defaultValue, lookupTable);
                    actions.add(qipDictionaryAction);
                }
                case CONDITIONAL -> {
                    setConditionalExpression(action.getTransformation().getParameters(), processedMapping);
                    return processedMapping;
                }
                case EXPRESSION -> {
                    setCommonExpression(action.getTransformation().getParameters().get(0), processedMapping);
                    return processedMapping;
                }
                case TRIM -> {
                    List<String> params = transformation.getParameters();
                    if (params.size() != 1) {
                        throw new SnapshotCreationException("Wrong number of parameters for 'trim' transformation.");
                    }
                    Action trimAction = switch (params.get(0)) {
                        case "left" -> new TrimLeft();
                        case "right" -> new TrimRight();
                        case "both" -> new Trim();
                        default -> {
                            String message = String.format(
                                    "Wrong 'side' parameter value for 'trim' transformation: %s.", params.get(0));
                            throw new SnapshotCreationException(message);
                        }
                    };
                    actions.add(trimAction);
                }
                case REPLACE_ALL -> {
                    List<String> params = transformation.getParameters();
                    if (params.size() != 2) {
                        throw new SnapshotCreationException("Wrong number of parameters for 'replaceAll' transformation.");
                    }
                    ReplaceAll replaceAllAction = new ReplaceAll();
                    replaceAllAction.setMatch(params.get(0));
                    replaceAllAction.setNewString(params.get(1));
                    actions.add(replaceAllAction);
                }
            }
            processedMapping.getInputField().get(0).setActions(actions);
        }

        return processedMapping == null ? mapping : processedMapping;
    }

    private void setConditionalExpression(List<String> parameters, Mapping mapping) {
        String conditionalExpression = "IF ( " + parameters.get(0)
                + " , " + parameters.get(1)
                + " , " + parameters.get(2)
                + " )";

        setCommonExpression(conditionalExpression, mapping);
    }

    private void setCommonExpression(String expression, Mapping mapping) {
        ToAtlasMapExpressionConverter expressionConverter = new ToAtlasMapExpressionConverter();
        List<Field> inputFields = isNull(mapping.getInputFieldGroup())
                ? mapping.getInputField()
                : mapping.getInputFieldGroup().getField();
        String convertedExpression = expressionConverter.convert(expression, fieldReference -> inputFields.stream()
                .filter(field -> {  // match field kind
                    FieldKind kind = fieldReference.kind();
                    return (FieldKind.CONSTANT.equals(kind)
                                && ((field instanceof ConstantField) || field instanceof GeneratedField))
                            || (FieldKind.PROPERTY.equals(kind)
                                && (field instanceof PropertyField)
                                && !((PropertyField) field).getScope().equals(CURRENT_SCOPE))
                            || (FieldKind.PROPERTY.equals(kind)
                                && !(field instanceof ConstantField)
                                && !(field instanceof PropertyField)
                                && (fieldReference.path().size() > 1)
                                && (field.getDocId().equals(fieldReference.path().get(0))))
                            || (FieldKind.HEADER.equals(kind)
                                && (field instanceof PropertyField)
                                && ((PropertyField) field).getScope().equals(CURRENT_SCOPE))
                            || (FieldKind.BODY.equals(kind)
                                && !(field instanceof ConstantField)
                                && !(field instanceof PropertyField));
                })
                .filter(field -> { // match field path
                    String path = field.getPath();
                    if (path.startsWith("/")) {
                        path = path.substring(1);
                    }
                    if (field instanceof PropertyField) {
                        path = path.replaceAll("^(current|camelExchangeProperty)/", "");
                    }
                    List<String> referencePath = (FieldKind.PROPERTY.equals(fieldReference.kind()) && fieldReference.path().size() > 1)
                            ? fieldReference.path().subList(1, fieldReference.path().size())
                            : fieldReference.path();
                    return Arrays.stream(path.split("/"))
                            .map(name -> name.replaceAll("<>", ""))
                            .toList()
                            .equals(referencePath);
                })
                .map(field -> String.format("%s:%s", field.getDocId(), field.getPath()))
                .findFirst()
                .orElseThrow(() -> new SnapshotCreationException(
                        MAPPING_CREATION_ERROR_MESSAGE.concat(UNABLE_TO_INTERPRETER_EXPRESSION).concat(expression)
                                .concat(" ").concat(FAILED_TO_RESOLVE_FIELD_REFERENCE).concat(fieldReference.toString()))));
        mapping.setExpression(convertedExpression);
    }

    private void validateForSupportedMappings(List<Field> inputFields, Field outputField, MappingAction action) {

        if (inputFields.isEmpty() || outputField == null) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE.concat(EMPTY_FIELDS_ERROR_MESSAGE));
        }

        // Array and primitive into array without transformation not supported
        if (
                inputFields.stream().anyMatch(field -> field.getCollectionType().equals(CollectionType.ARRAY))
                        && inputFields.stream().anyMatch(field -> field.getCollectionType().equals(CollectionType.NONE))
                        && outputField.getCollectionType().equals(CollectionType.ARRAY)
                        && action.getTransformation() == null
        ) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE.concat(COMBINE_ARRAY_AND_PRIMITIVE_INTO_ARRAY_ERROR_MESSAGE));
        }

        // Many array to array without transformation case not supported
        if (
                inputFields.size() > 1
                        && inputFields.stream().anyMatch(field -> field.getCollectionType().equals(CollectionType.ARRAY))
                        && outputField.getCollectionType().equals(CollectionType.ARRAY)
                        && action.getTransformation() == null
        ) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE.concat(COMBINE_SEVERAL_ARRAYS_ERROR_MESSAGE));
        }

        //Many primitive to one without transformation not supported
        if (inputFields.size() > 1 && action.getTransformation() == null && !outputField.getCollectionType().equals(CollectionType.ARRAY)) {
            throw new SnapshotCreationException(MAPPING_CREATION_ERROR_MESSAGE.concat(MULTIPLE_FIELDS_AGGREGATION_ERROR_MESSAGE));
        }
    }
}
