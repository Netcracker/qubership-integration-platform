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

package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.parser.util.DeserializationUtils;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.extensions.SwaggerParserExtension;
import io.swagger.v3.parser.core.models.ParseOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.qubership.integration.platform.parsers.Parser;
import org.qubership.integration.platform.parsers.SpecificationParser;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedEnvironmentImpl;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedOperationImpl;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.parsers.resolvers.SwaggerSchemaResolver;
import org.qubership.integration.platform.parsers.schemas.Processor;
import org.qubership.integration.platform.parsers.schemas.SchemaProcessor;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.parsers.schemas.SchemasConstants.DEFAULT_SCHEMA_CLASS;


/**
 * Parses an OpenAPI or Swagger specification into a persistence-free system model.
 *
 * <p>The parser produces the operations with their request and response schemas, plus the servers
 * the specification declares as {@link ParsedEnvironment} values with URL placeholders resolved to
 * their defaults. It performs no environment side effect and touches no catalog storage: the catalog
 * wrapper reads the declared environments and reconciles them against the owning system.
 * {@link #parseOpenApi} and {@link #toSystemModel} let that wrapper parse the source once and reuse
 * the {@link OpenAPI} model for both operations and environments.
 */
@Slf4j
@Parser("swagger")
public class SwaggerSpecificationParser implements SpecificationParser {
    private static final String SWAGGER_LABEL = "swagger";
    private static final String OPEN_API_LABEL = "openapi";
    private static final String INVALID_SWAGGER_FILE_ERROR_MESSAGE = "Error during processing file";
    private static final String PARAMETERS_NODE = "parameters";
    private static final String ERROR_CONVERTING_OPERATION_MESSAGE = "Error during converting Operation to JSON";
    private static final String OPENAPI_32_VERSION_PREFIX = "3.2";
    private static final String OPENAPI_31_FALLBACK_VERSION = "3.1.0";
    private static final String ID_SEPARATOR = "-";

    private final SwaggerSchemaResolver swaggerSchemaResolver;
    private final OpenApiMapperResolver openApiMapperResolver;

    private final Map<String, SchemaProcessor> schemaProcessorMap = new HashMap<>();

    public SwaggerSpecificationParser(
            SwaggerSchemaResolver swaggerSchemaResolver,
            List<SchemaProcessor> schemaProcessors,
            OpenApiMapperResolver openApiMapperResolver
    ) {
        this.swaggerSchemaResolver = swaggerSchemaResolver;
        this.openApiMapperResolver = openApiMapperResolver;
        for (SchemaProcessor schemaProcessor : schemaProcessors) {
            Processor processorAnnotation = schemaProcessor.getClass().getAnnotation(Processor.class);
            if (processorAnnotation != null) {
                this.schemaProcessorMap.put(processorAnnotation.value(), schemaProcessor);
            }
        }
    }

    @Override
    public ParsedSystemModel parseSpecification(
            String groupId,
            Collection<SpecificationSource> sources,
            Consumer<String> messageHandler
    ) {
        try {
            OpenAPI importedOpenAPI = parseOpenApi(sources, messageHandler);
            return toSystemModel(importedOpenAPI, messageHandler);
        } catch (Exception e) {
            throw new SpecificationParserException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    /**
     * Parses the first source into an {@link OpenAPI} model, first downgrading an unsupported 3.2
     * version to 3.1. The catalog wrapper reuses the returned model for environment resolution.
     */
    public OpenAPI parseOpenApi(Collection<SpecificationSource> sources, Consumer<String> messageHandler) {
        String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
        JsonNode specificationNode = DeserializationUtils.deserializeIntoTree(specificationText, "file");
        String parseableText = downgradeUnsupportedOpenApiVersion(specificationNode, specificationText, messageHandler.andThen(log::warn));
        OpenAPI importedOpenAPI = getSwaggerParser(specificationNode).readContents(parseableText, null, new ParseOptions()).getOpenAPI();
        if (importedOpenAPI == null) {
            throw new SpecificationParserException(INVALID_SWAGGER_FILE_ERROR_MESSAGE);
        }
        return importedOpenAPI;
    }

    /**
     * Builds the system model from an already-parsed {@link OpenAPI}: the declared version, the
     * operations with their resolved request and response schemas, and the declared servers as
     * environments.
     */
    public ParsedSystemModel toSystemModel(OpenAPI importedOpenAPI, Consumer<String> messageHandler) {
        ObjectMapper specMapper = openApiMapperResolver.forVersion(importedOpenAPI.getSpecVersion());
        List<ParsedOperation> operationList = separate(importedOpenAPI, specMapper, messageHandler.andThen(log::warn));
        return ParsedSystemModelImpl.builder()
                .version(importedOpenAPI.getInfo().getVersion())
                .operations(operationList)
                .environments(toParsedEnvironments(importedOpenAPI.getServers()))
                .build();
    }

    /**
     * Maps the declared servers to environments, resolving each URL's placeholders to their default
     * values. The server description becomes the environment name when present; the catalog wrapper
     * supplies a fallback name for a server that declares none.
     */
    private List<ParsedEnvironment> toParsedEnvironments(List<Server> servers) {
        if (servers == null) {
            return new ArrayList<>();
        }
        return servers.stream()
                .map(server -> (ParsedEnvironment) ParsedEnvironmentImpl.builder()
                        .name(server.getDescription())
                        .address(resolveServerAddress(server))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Resolves a server URL to a concrete address: substitutes each variable with its default value
     * and strips leading and trailing slashes.
     */
    private String resolveServerAddress(Server server) {
        final String[] url = {server.getUrl()};
        if (server.getVariables() != null && !server.getVariables().isEmpty()) {
            server.getVariables().forEach((key, value) -> {
                if (url[0].contains("{" + key + "}")) {
                    url[0] = url[0].replace("{" + key + "}", value.getDefault());
                }
            });
        }
        url[0] = StringUtils.strip(url[0], "/");
        return url[0];
    }

    private SwaggerParserExtension getSwaggerParser(JsonNode node) {
        if (node.has(SWAGGER_LABEL)) {
            return new SwaggerConverter();
        } else if (node.has(OPEN_API_LABEL)) {
            return new OpenAPIV3Parser();
        } else {
            throw new SpecificationParserException(INVALID_SWAGGER_FILE_ERROR_MESSAGE);
        }
    }

    /**
     * Rewrites an OpenAPI 3.2 version field to 3.1 so the specification can be parsed.
     *
     * <p>swagger-parser 2.1.x ships no 3.2 deserializer and rejects the version outright.
     * OpenAPI 3.2 stays backward compatible with 3.1, so 3.2 documents are parsed as 3.1;
     * 3.2-only constructs (the QUERY method, {@code $self}, extended media-type keys) are
     * dropped rather than failing the import. Only the text handed to the parser changes —
     * the stored specification source keeps its original version.
     */
    private String downgradeUnsupportedOpenApiVersion(JsonNode specificationNode, String specificationText, Consumer<String> messageHandler) {
        try {
            if (specificationNode == null || !specificationNode.has(OPEN_API_LABEL)) {
                return specificationText;
            }
            String version = specificationNode.get(OPEN_API_LABEL).asText("");
            if (!version.startsWith(OPENAPI_32_VERSION_PREFIX)) {
                return specificationText;
            }
            ((ObjectNode) specificationNode).put(OPEN_API_LABEL, OPENAPI_31_FALLBACK_VERSION);
            messageHandler.accept(String.format(
                    "OpenAPI %s imported with the 3.1 parser. 3.2-only features may be dropped. ",
                    version));
            return specificationNode.toString();
        } catch (Exception e) {
            log.warn("Could not normalize the OpenAPI version; passing the specification to the parser unchanged", e);
            return specificationText;
        }
    }

    private List<ParsedOperation> separate(OpenAPI importedOpenAPI, ObjectMapper specMapper, Consumer<String> messageHandler) {
        Map<String, PathItem> pathItems = new LinkedHashMap<>();
        if (importedOpenAPI.getPaths() != null) {
            importedOpenAPI.getPaths().forEach((pathName, pathItem) -> {
                if (pathItem != null && !pathItem.readOperations().isEmpty()) {
                    pathItems.put(pathName, pathItem);
                }
            });
        }
        return generateOperationsList(pathItems, importedOpenAPI, specMapper, messageHandler);
    }

    private List<ParsedOperation> generateOperationsList(
            Map<String, PathItem> pathItems,
            OpenAPI importedOpenAPI,
            ObjectMapper specMapper,
            Consumer<String> messageHandler
    ) {
        List<ParsedOperation> generatedOperations = new ArrayList<>();
        List<String> operationNames = new ArrayList<>();
        int operationNamesCounter = 0;
        String operationPostfix;

        try {
            Components importedComponents = new Components();
            if (importedOpenAPI.getComponents() != null) {
                importedComponents = importedOpenAPI.getComponents();
            }
            JsonNode importedComponentsString = specMapper.readTree(specMapper.writeValueAsString(importedComponents));
            for (var pathEntry : pathItems.entrySet()) {
                String pathName = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();
                ArrayNode pathItemParams = specMapper.createArrayNode();
                if (pathItem.getParameters() != null) {
                    pathItemParams = (ArrayNode) specMapper.readTree(specMapper.writeValueAsString(pathItem.getParameters()));
                }
                for (var method : pathItem.readOperationsMap().entrySet()) {
                    io.swagger.v3.oas.models.Operation operation = method.getValue();
                    ObjectNode specification = (ObjectNode) specMapper.readTree(specMapper.writeValueAsString(operation));
                    if (!pathItemParams.isEmpty()) {
                        ArrayNode specificationParameters = specMapper.createArrayNode();
                        if (specification.has(PARAMETERS_NODE)) {
                            specificationParameters.addAll((ArrayNode) specification.get(PARAMETERS_NODE));
                        }
                        specificationParameters.addAll(pathItemParams);
                        specification.set(PARAMETERS_NODE, specificationParameters);
                    }
                    ParsedOperation resultOperation = ParsedOperationImpl.builder()
                            .path(pathName)
                            .name(operation.getOperationId())
                            .method(method.getKey().name())
                            .specification(specification)
                            .requestSchema(generateRequest(operation, importedComponentsString, specMapper))
                            .responseSchemas(generateResponsesMap(operation, importedComponentsString, specMapper))
                            .build();

                    if (resultOperation.getName() == null) {
                        StringBuilder operationName = new StringBuilder(generateName(pathName, method.getKey().name(), operation));
                        warnAboutEmptyOperationId(pathName, method.getKey().name(), messageHandler);

                        for (String generatedOperationName : operationNames) {
                            if (generatedOperationName.contentEquals(operationName)) {
                                operationNamesCounter = operationNamesCounter + 1;
                            }
                        }

                        operationNames.add(operationName.toString());

                        if (operationNamesCounter != 0) {
                            operationPostfix = ID_SEPARATOR + operationNamesCounter;
                            operationName.append(operationPostfix);
                        }

                        operationNamesCounter = 0;

                        resultOperation.setName(operationName.toString());
                    }
                    generatedOperations.add(resultOperation);
                }
            }
        } catch (IOException e) {
            throw new SpecificationParserException(ERROR_CONVERTING_OPERATION_MESSAGE, e.getCause());
        }
        return generatedOperations;
    }

    private void warnAboutEmptyOperationId(String path, String method, Consumer<String> messageHandler) {
        String message = String.format("Operation has no identifier: %s - %s. ", path, method);
        messageHandler.accept(message);
    }

    private Map<String, JsonNode> generateRequest(io.swagger.v3.oas.models.Operation operation, JsonNode importedComponents, ObjectMapper specMapper) {
        Map<String, JsonNode> result = new HashMap<>();
        if (operation.getRequestBody() != null) {
            result = generateContentMap(operation.getRequestBody().getContent(), importedComponents, specMapper);
        }
        List<Parameter> parameters = operation.getParameters();
        if (parameters != null && !parameters.isEmpty()) {
            result.put("parameters", specMapper.valueToTree(parameters));
        }
        return result;
    }

    private Map<String, JsonNode> generateResponsesMap(io.swagger.v3.oas.models.Operation operation, JsonNode importedComponents, ObjectMapper specMapper) {
        Map<String, JsonNode> result = new HashMap<>();
        if (operation.getResponses() != null) {
            result = operation.getResponses()
                    .keySet()
                    .stream()
                    .map(responseCode -> {
                        Map responseCodeMap;
                        JsonNode responseCodeMapNode = specMapper.createObjectNode();
                        if (operation.getResponses().get(responseCode).getContent() != null) {
                            responseCodeMap = generateContentMap(operation.getResponses().get(responseCode).getContent(), importedComponents, specMapper);
                            responseCodeMapNode = specMapper.convertValue(responseCodeMap, JsonNode.class);
                        }
                        return new MutablePair<>(responseCode, responseCodeMapNode);
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return result;
    }

    private Map<String, JsonNode> generateContentMap(Content content, JsonNode importedComponents, ObjectMapper specMapper) {
        return content.keySet()
                .stream()
                .map(mediaType -> {
                    Schema<?> schema = content.get(mediaType).getSchema();
                    if (schema == null) {
                        return new MutablePair<>(mediaType, specMapper.createObjectNode());
                    }
                    SchemaProcessor schemaProcessor = schemaProcessorMap.getOrDefault(schema.getClass().getSimpleName(),
                            schemaProcessorMap.get(DEFAULT_SCHEMA_CLASS));

                    MutablePair<String, String> processedSchemaPair = schemaProcessor.process(schema, specMapper);
                    String ref = processedSchemaPair.left;
                    String schemaAsString = ref != null
                            ? swaggerSchemaResolver.resolveRef(ref, importedComponents)
                            : processedSchemaPair.right;

                    return new MutablePair<>(mediaType, schemaProcessor.applySchemaType(schemaAsString));
                })
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String generateName(String url, String method, io.swagger.v3.oas.models.Operation operation) {
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                url = url.replace("/{" + parameter.getName() + "}", "");
            }
        }
        if (url.isEmpty()) {
            url = "/";
        }

        StringBuilder operationId = new StringBuilder(method.toLowerCase());
        if (!"/".equals(url)) {
            int slashIndex = url.lastIndexOf("/");
            if (slashIndex + 1 == url.length()) {
                slashIndex = url.substring(0, url.length() - 2).lastIndexOf("/");
            }
            operationId.append(StringUtils.capitalize(url.substring(++slashIndex)));
        } else {
            if (operation.getParameters() != null) {
                for (Parameter parameter : operation.getParameters()) {
                    operationId
                            .append("{")
                            .append(StringUtils.capitalize(parameter.getName()))
                            .append("}");
                }
            } else {
                operationId.append("/");
            }
        }

        return "[" + operationId + "]";
    }
}
