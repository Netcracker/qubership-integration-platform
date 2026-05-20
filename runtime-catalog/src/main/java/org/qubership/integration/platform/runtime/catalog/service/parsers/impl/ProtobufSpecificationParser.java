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

package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.squareup.wire.schema.Field;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.internal.parser.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.Parser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.service.schemas.SchemasConstants.*;


@Slf4j
@Service
@Parser("protobuf")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class ProtobufSpecificationParser implements SpecificationParser {
    private static final String JAVA_PACKAGE_OPTION_NAME = "java_package";
    private static final Pattern MAP_TYPE_REGEX =
            Pattern.compile("^map<\\s*([a-zA-Z0-9_\\-.]+)\\s*,\\s*([a-zA-Z0-9_\\-.]+)\\s*>$");

    private final SystemModelRepository systemModelRepository;
    private final ParserUtils parserUtils;
    private final ObjectMapper objectMapper;

    @Autowired
    public ProtobufSpecificationParser(
            SystemModelRepository systemModelRepository,
            ParserUtils parserUtils,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper
    ) {
        this.systemModelRepository = systemModelRepository;
        this.parserUtils = parserUtils;
        this.objectMapper = objectMapper;
    }

    @Override
    public SystemModel enrichSpecificationGroup(
            SpecificationGroup group,
            Collection<SpecificationSource> sources,
            Set<String> oldSystemModelsIds, boolean isDiscovered,
            Consumer<String> messageHandler
    ) {
        try {
            SystemModel systemModel;
            String systemModelName = parserUtils.defineVersionName(group, null);
            String systemModelVersion = parserUtils.defineVersion(group, null);

            List<ProtoFileElement> protoFiles = parseProtoFiles(sources);
            ObjectNode typeDefinitions = buildTypeDefinitions(protoFiles);
            List<Operation> operations = getOperations(protoFiles, typeDefinitions);

            String systemModelId = buildId(group.getId(), systemModelName);
            systemModel = SystemModel.builder().id(systemModelId).build();

            checkSpecId(oldSystemModelsIds, systemModelId);

            setOperationIds(systemModelId, operations, messageHandler.andThen(log::warn));

            systemModel = systemModelRepository.save(systemModel);
            systemModel.setName(systemModelName);
            systemModel.setVersion(systemModelVersion);

            operations.forEach(systemModel::addProvidedOperation);
            group.addSystemModel(systemModel);

            return systemModel;
        } catch (SpecificationSimilarIdException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private List<ProtoFileElement> parseProtoFiles(Collection<SpecificationSource> sources) {
        return sources.stream().filter(ProtobufSpecificationParser::isProtobufFile)
                .map(ProtobufSpecificationParser::parseProtobuf)
                .collect(Collectors.toList());
    }

    private ObjectNode buildTypeDefinitions(Collection<ProtoFileElement> protoFiles) {
        ObjectNode definitions = objectMapper.createObjectNode();
        Stream<Map.Entry<String, JsonNode>> builtinTypes = createBuiltinTypes().entrySet().stream();
        Set<String> typeNames = protoFiles.stream()
            .flatMap(protoFile -> protoFile.getTypes().stream()
                .map(typeElement -> buildFullyQualifiedName(protoFile.getPackageName(), typeElement.getName())))
            .collect(Collectors.toSet());
        Stream<Map.Entry<String, JsonNode>> types = protoFiles.stream()
                .flatMap(protoFile -> protoFile.getTypes().stream()
                        .flatMap(typeElement -> buildTypeDefinitionsRecursively(
                                typeElement,
                                protoFile.getPackageName(),
                                getTypeNameResolver(protoFile.getPackageName(), typeNames))))
                .filter(entry -> nonNull(entry.getValue()));
        Stream.concat(builtinTypes, types)
                .forEach(entry -> definitions.set(entry.getKey(), entry.getValue()));
        return definitions;
    }

    private Function<String, String> getTypeNameResolver(String packageName, Set<String> typeNames) {
        return typeName -> {
            // https://protobuf.dev/programming-guides/proto3/#name-resolution
            // First the innermost scope is searched, then the next-innermost, and so on,
            // with each package considered to be “inner” to its parent package.
            // A leading ‘.’ (for example, .foo.bar.Baz) means to start from the outermost scope instead.
            String pkg = packageName;
            boolean nameStartsWithDot = typeName.startsWith(".");
            while (!pkg.isEmpty()) {
                String name = buildFullyQualifiedName(pkg, nameStartsWithDot ? typeName.substring(1) : typeName);
                if (typeNames.contains(name)) {
                    return name;
                }
                int index = nameStartsWithDot ? pkg.indexOf(".") : pkg.lastIndexOf(".");
                pkg = index < 0 ? "" : (nameStartsWithDot ? pkg.substring(index + 1) : pkg.substring(0, index));
            }
            return typeName;
        };
    }

    private Stream<Map.Entry<String, JsonNode>> buildTypeDefinitionsRecursively(
            TypeElement typeElement,
            String packageName,
            Function<String, String> typeResolver
    ) {
        String name = buildFullyQualifiedName(packageName, typeElement.getName());
        return Stream.concat(
            Stream.of(Map.entry(name, buildTypeDefinition(typeElement, packageName, typeResolver))),
            typeElement.getNestedTypes().stream().flatMap(nestedType -> buildTypeDefinitionsRecursively(nestedType, name, typeResolver))
        );
    }

    private Map<String, JsonNode> createBuiltinTypes() {
        Map<String, JsonNode> m = new HashMap<>();
        m.put("float", createFloat64Schema());
        m.put("double", createFloat64Schema());
        m.put("int32", createInt32Schema());
        m.put("int64", createInt64Schema());
        m.put("uint32", createInt32Schema());
        m.put("uint64", createInt64Schema());
        m.put("sint32", createInt32Schema());
        m.put("sint64", createInt64Schema());
        m.put("fixed32", createInt32Schema());
        m.put("fixed64", createInt64Schema());
        m.put("sfixed32", createInt32Schema());
        m.put("sfixed64", createInt64Schema());
        m.put("bytes", createBytesSchema());
        return m;
    }

    private JsonNode buildTypeDefinition(TypeElement typeElement, String packageName, Function<String, String> typeResolver) {
        if (typeElement instanceof MessageElement messageElement) {
            return buildMessageDefinition(messageElement, packageName, typeResolver);
        } else if (typeElement instanceof EnumElement enumElement) {
            return buildEnumDefinition(enumElement);
        } else {
            return null;
        }
    }

    private JsonNode buildEnumDefinition(EnumElement enumElement) {
        ObjectNode definition = objectMapper.createObjectNode();
        addGeneralProperties(definition, enumElement);
        definition.put("type", "string");
        ArrayNode values = objectMapper.createArrayNode();
        enumElement.getConstants().stream().map(EnumConstantElement::getName).forEach(values::add);
        definition.set("enum", values);
        return definition;
    }

    private JsonNode buildMessageDefinition(MessageElement messageElement, String packageName, Function<String, String> typeResolver) {
        ObjectNode definition = objectMapper.createObjectNode();
        addGeneralProperties(definition, messageElement);

        Set<String> nestedTypes = messageElement.getNestedTypes().stream().map(TypeElement::getName)
                .collect(Collectors.toSet());

        definition.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        Stream.concat(
                messageElement.getFields().stream(),
                messageElement.getOneOfs().stream().map(OneOfElement::getFields).flatMap(Collection::stream)
        ).forEach(field -> {
            String name = Optional.ofNullable(field.getJsonName()).orElse(field.getName());
            properties.set(name, buildFieldType(field, typeName -> {
                String scopedName = nestedTypes.contains(typeName)
                        ? String.join(".", packageName, messageElement.getName(), typeName)
                        : typeResolver.apply(typeName);
                return getFullyQualifiedName(scopedName, packageName);
            }));
        });
        definition.set("properties", properties);

        List<String> requiredFieldNames = messageElement.getFields().stream()
                .filter(field -> Field.Label.REQUIRED.equals(field.getLabel()))
                .map(FieldElement::getName)
                .toList();
        if (!requiredFieldNames.isEmpty()) {
            ArrayNode required = objectMapper.createArrayNode();
            requiredFieldNames.forEach(required::add);
            definition.set("required", required);
        }
        definition.put("additionalProperties", false);

        return definition;
    }

    private JsonNode buildFieldType(FieldElement fieldElement, Function<String, String> typeResolver) {
        ObjectNode type;

        Matcher m = MAP_TYPE_REGEX.matcher(fieldElement.getType());
        if (m.matches()) {
            type = objectMapper.createObjectNode();
            type.put("type", "object");
            type.set("additionalProperties", buildTypeNode(m.group(2), typeResolver));
        } else {
            type = buildTypeNode(fieldElement.getType(), typeResolver);
            if (Field.Label.REPEATED.equals(fieldElement.getLabel())) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", "array");
                node.set("items", type);
                type = node;
            }
        }
        return type;
    }

    private ObjectNode buildTypeNode(String typeName, Function<String, String> typeResolver) {
        return switch (typeName) {
            case "double", "float",
                    "int32", "int64",
                    "uint32", "uint64",
                    "sint32", "sint64",
                    "fixed32", "fixed64",
                    "sfixed32", "sfixed64",
                    "bytes" -> buildReferenceType(typeName);
            case "bool" -> buildSimpleType("boolean");
            case "string" -> buildSimpleType("string");
            default -> buildReferenceType(typeResolver.apply(typeName));
        };
    }

    private String getFullyQualifiedName(String name, String packageName) {
        return isFullyQualifiedName(name) ? name : buildFullyQualifiedName(packageName, name);
    }

    private ObjectNode buildReferenceType(String typeName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("$ref", "#/definitions/" + typeName);
        return node;
    }

    private ObjectNode buildSimpleType(String name) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", name);
        return node;
    }

    private static boolean isFullyQualifiedName(String name) {
        return name.contains(".");
    }

    private static String buildFullyQualifiedName(String packageName, String name) {
        return String.join(".", packageName, name);
    }

    private void addGeneralProperties(ObjectNode typeDefinition, TypeElement typeElement) {
        String doc = typeElement.getDocumentation();
        if (!StringUtils.isBlank(doc)) {
            typeDefinition.put("description", doc);
        }
    }

    private List<Operation> getOperations(Collection<ProtoFileElement> protoFiles, ObjectNode typeDefinitions) {
        return protoFiles.stream().flatMap(protoFile -> extractOperations(protoFile, typeDefinitions))
                .collect(Collectors.toList());
    }

    private static boolean isProtobufFile(SpecificationSource source) {
        return source.getName().endsWith(".proto");
    }

    private static ProtoFileElement parseProtobuf(SpecificationSource source) {
        Location location = Location.get(source.getName());
        char[] content = source.getSource().toCharArray();
        ProtoParser parser = new ProtoParser(location, content);
        return parser.readProtoFile();
    }

    private JsonNode createFloat64Schema() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "number");
        return node;
    }

    private JsonNode createInt32Schema() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "number");
        node.put("format", "int32");
        return node;
    }

    private JsonNode createInt64Schema() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "number");
        node.put("format", "int64");
        return node;
    }

    private JsonNode createBytesSchema() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("format", "bytes");
        return node;
    }

    private Stream<Operation> extractOperations(ProtoFileElement protoFile, ObjectNode typeDefinitions) {
        return protoFile.getServices().stream().flatMap(service ->
            service.getRpcs().stream().map(rpc -> {
                String packageName = protoFile.getPackageName();
                String javaPackageName = getJavaPackageName(protoFile);
                String operationName = service.getName() + "." + rpc.getName();
                Operation operation = new Operation();
                operation.setName(operationName);
                operation.setMethod(rpc.getName());
                operation.setPath(buildFullyQualifiedName(javaPackageName, service.getName()));

                JsonNode requestSchema = buildSchema(packageName, operationName, rpc.getRequestType(), "requests", typeDefinitions);
                JsonNode responseSchema = buildSchema(packageName, operationName, rpc.getResponseType(), "responses", typeDefinitions);

                operation.setRequestSchema(Map.of("application/json", requestSchema));

                ObjectNode responseSpecification = objectMapper.createObjectNode();
                responseSpecification.set("application/json", responseSchema);

                operation.setResponseSchemas(Map.of("200", responseSpecification));
                operation.setSpecification(buildOperationSpecification(rpc, operationName, requestSchema, responseSchema));
                return operation;
            })
        );
    }

    private String getJavaPackageName(ProtoFileElement protofile) {
        return protofile.getOptions().stream()
            .filter(option -> JAVA_PACKAGE_OPTION_NAME.equals(option.getName()))
            .findFirst()
            .map(OptionElement::getValue)
            .map(String::valueOf)
            .orElse(protofile.getPackageName());
    }

    private JsonNode buildSchema(
            String packageName,
            String operationName,
            String typeName,
            String kind,
            ObjectNode typeDefinitions
    ) {
        String fullyQualifiedTypeName = getFullyQualifiedName(typeName, packageName);
        ObjectNode node = buildReferenceType(fullyQualifiedTypeName);
        node.put(SCHEMA_ID_NODE_NAME, String.format("http://system.catalog/schemas/%s/%s",
                kind, buildFullyQualifiedName(packageName, operationName)));
        node.put(SCHEMA_HEADER_NODE_NAME, "http://json-schema.org/draft-07/schema#");
        node.set(DEFINITIONS_NODE_NAME, filterRelatedTypes(typeDefinitions, fullyQualifiedTypeName));
        return node;
    }

    ObjectNode filterRelatedTypes(ObjectNode typeDefinitions, String name) {
        ObjectNode result = objectMapper.createObjectNode();
        collectRelatedTypes(result, typeDefinitions, name);
        return result;
    }

    private void collectRelatedTypes(ObjectNode result, ObjectNode typeDefinitions, String name) {
        if (result.has(name)) {
            return;
        }
        JsonNode definition = typeDefinitions.get(name);
        if (nonNull(definition) && definition.isObject()) {
            result.set(name, definition);
            getReferencedTypeNames(definition).forEach(referencedTypeName ->
                    collectRelatedTypes(result, typeDefinitions, referencedTypeName));
        }
    }

    private Collection<String> getReferencedTypeNames(JsonNode node) {
        if (isNull(node)) {
            return Collections.emptyList();
        }

        if (node.has("$ref")) {
            String reference = node.get("$ref").asText();
            String referencedType = reference.substring(reference.lastIndexOf("/") + 1);
            return Collections.singletonList(referencedType);
        }

        JsonNode typeNode = node.get("type");
        if (isNull(typeNode)) {
            return Collections.emptyList();
        }

        if (typeNode.isObject()) {
            return getReferencedTypeNames(typeNode);
        }

        if (typeNode.asText().equals("object")) {
            return Stream.of("properties", "additionalProperties")
                    .map(node::get)
                    .filter(Objects::nonNull)
                    .map(JsonNode::fields)
                    .flatMap(fields -> {
                        Collection<String> types = new ArrayList<>();
                        fields.forEachRemaining(entry -> types.addAll(getReferencedTypeNames(entry.getValue())));
                        return types.stream();
                    })
                    .toList();
        }
        if (typeNode.asText().equals("array")) {
            return getReferencedTypeNames(node.get("items"));
        }

        return Collections.emptyList();
    }

    private JsonNode buildOperationSpecification(RpcElement rpc, String operationName, JsonNode requestSchema, JsonNode responseSchema) {
        ObjectNode specification = objectMapper.createObjectNode();

        String doc = rpc.getDocumentation();
        if (!StringUtils.isBlank(doc)) {
            specification.put("summary", doc);
        }
        specification.put("operationId", operationName);

        specification
                .with("responses")
                .with("200")
                .with("content")
                .with("application/json")
                .set("schema", responseSchema);

        specification
                .with("requestBody")
                .with("content")
                .with("application/json")
                .set("schema", requestSchema);

        return specification;
    }
}
