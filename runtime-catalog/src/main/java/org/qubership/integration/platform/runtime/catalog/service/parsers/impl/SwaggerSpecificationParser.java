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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.Parser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.swagger.SwaggerSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.schemas.Processor;
import org.qubership.integration.platform.runtime.catalog.service.schemas.SchemaProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.service.schemas.SchemasConstants.DEFAULT_SCHEMA_CLASS;


@Slf4j
@Service
@Parser("swagger")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class SwaggerSpecificationParser implements SpecificationParser {
    private static final String SWAGGER_LABEL = "swagger";
    private static final String OPEN_API_LABEL = "openapi";
    private static final String INVALID_SWAGGER_FILE_ERROR_MESSAGE = "Error during processing file";
    private static final String PARAMETERS_NODE = "parameters";
    private static final String ERROR_CONVERTING_OPERATION_MESSAGE = "Error during converting Operation to JSON";

    private final SystemModelRepository systemModelRepository;
    private final SwaggerSchemaResolver swaggerSchemaResolver;
    private final ObjectMapper objectMapper;
    private final ParserUtils parserUtils;
    private final EnvironmentBaseService environmentBaseService;

    private final Map<String, SchemaProcessor> schemaProcessorMap = new HashMap<>();

    @Autowired
    public SwaggerSpecificationParser(
            SystemModelRepository systemModelRepository,
            SwaggerSchemaResolver swaggerSchemaResolver,
            List<SchemaProcessor> schemaProcessors,
            @Qualifier("openApiObjectMapper") ObjectMapper objectMapper,
            ParserUtils parserUtils,
            EnvironmentBaseService environmentBaseService
    ) {
        this.systemModelRepository = systemModelRepository;
        this.swaggerSchemaResolver = swaggerSchemaResolver;
        this.objectMapper = objectMapper;
        this.parserUtils = parserUtils;
        this.environmentBaseService = environmentBaseService;
        for (SchemaProcessor schemaProcessor : schemaProcessors) {
            Processor processorAnnotation = schemaProcessor.getClass().getAnnotation(Processor.class);
            if (processorAnnotation != null) {
                this.schemaProcessorMap.put(processorAnnotation.value(), schemaProcessor);
            }
        }
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
            String specificationText = sources.stream().map(SpecificationSource::getSource).findFirst().orElse("");
            OpenAPI importedOpenAPI = getSwaggerParser(specificationText).readContents(specificationText, null, new ParseOptions()).getOpenAPI();
            String systemModelName = parserUtils.defineVersionName(group, importedOpenAPI);
            String systemModelId = buildId(group.getId(), systemModelName);
            List<Operation> operationList = separate(importedOpenAPI, messageHandler.andThen(log::warn));

            checkSpecId(oldSystemModelsIds, systemModelId);

            resolverSwaggerEnvironment(group, importedOpenAPI);

            systemModel = SystemModel.builder().id(systemModelId).build();

            systemModel = systemModelRepository.save(systemModel);
            systemModel.setName(systemModelName);
            systemModel.setVersion(parserUtils.defineVersion(group, importedOpenAPI));

            setOperationIds(systemModelId, operationList, messageHandler.andThen(log::warn));

            operationList.forEach(systemModel::addProvidedOperation);
            group.addSystemModel(systemModel);

            return systemModel;
        } catch (SpecificationSimilarIdException | SpecificationSimilarVersionException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    private SwaggerParserExtension getSwaggerParser(String specificationAsString) {
        JsonNode node = DeserializationUtils.deserializeIntoTree(specificationAsString, "file");
        if (node.has(SWAGGER_LABEL)) {
            return new SwaggerConverter();
        } else if (node.has(OPEN_API_LABEL)) {
            return new OpenAPIV3Parser();
        } else {
            throw new SpecificationImportException(INVALID_SWAGGER_FILE_ERROR_MESSAGE);
        }
    }

    private List<Operation> separate(OpenAPI importedOpenAPI, Consumer<String> messageHandler) {
        Map<String, Map<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation>> allOperations = new HashMap<>();
        if (importedOpenAPI.getPaths() != null) {
            for (String pathname : importedOpenAPI.getPaths().keySet()) {
                PathItem pathItem = importedOpenAPI.getPaths().get(pathname);
                if (!pathItem.readOperations().isEmpty()) {
                    allOperations.put(pathname, pathItem.readOperationsMap());
                }
            }
        }
        return generateOperationsList(allOperations, importedOpenAPI, messageHandler);
    }

    private List<Operation> generateOperationsList(
            Map<String, Map<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation>> allOperations,
            OpenAPI importedOpenAPI,
            Consumer<String> messageHandler
    ) {
        List<Operation> generatedOperations = new ArrayList<>();
        List<String> operationNames = new ArrayList<>();
        int operationNamesCounter = 0;
        String operationPostfix;

        try {
            Components importedComponents = new Components();
            if (importedOpenAPI.getComponents() != null) {
                importedComponents = importedOpenAPI.getComponents();
            }
            JsonNode importedComponentsString = objectMapper.readTree(objectMapper.writeValueAsString(importedComponents));
            for (var path : allOperations.entrySet()) {
                PathItem pathItem = importedOpenAPI.getPaths().get(path.getKey());
                ArrayNode pathItemParams = objectMapper.createArrayNode();
                if (pathItem.getParameters() != null) {
                    pathItemParams = (ArrayNode) objectMapper.readTree(objectMapper.writeValueAsString(pathItem.getParameters()));
                }
                for (var method : path.getValue().entrySet()) {
                    io.swagger.v3.oas.models.Operation operation = method.getValue();
                    ObjectNode specification = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(operation));
                    if (!pathItemParams.isEmpty()) {
                        ArrayNode specificationParameters = objectMapper.createArrayNode();
                        if (specification.has(PARAMETERS_NODE)) {
                            specificationParameters.addAll((ArrayNode) specification.get(PARAMETERS_NODE));
                        }
                        specificationParameters.addAll(pathItemParams);
                        specification.set(PARAMETERS_NODE, specificationParameters);
                    }
                    if (operation != null) {
                        Operation resultOperation = Operation.builder()
                                .path(path.getKey())
                                .name(operation.getOperationId())
                                .method(method.getKey().name())
                                .specification(specification)
                                .requestSchema(generateRequest(operation, importedComponentsString))
                                .responseSchemas(generateResponsesMap(operation, importedComponentsString))
                                .build();

                        if (resultOperation.getName() == null) {
                            StringBuilder operationName = new StringBuilder(generateName(path.getKey(), method.getKey().name(), operation));
                            warnAboutEmptyOperationId(path.getKey(), method.getKey().name(), messageHandler);

                            for (String generatedOperationName : operationNames) {
                                if (generatedOperationName.equals(operationName.toString())) {
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
            }
        } catch (IOException e) {
            throw new SpecificationImportException(ERROR_CONVERTING_OPERATION_MESSAGE, e.getCause());
        }
        return generatedOperations;
    }

    private void warnAboutEmptyOperationId(String path, String method, Consumer<String> messageHandler) {
        String message = String.format("Operation has no identifier: %s - %s. ", path, method);
        messageHandler.accept(message);
    }

    private Map<String, JsonNode> generateRequest(io.swagger.v3.oas.models.Operation operation, JsonNode importedComponents) {
        Map<String, JsonNode> result = new HashMap<>();
        if (operation.getRequestBody() != null) {
            result = generateContentMap(operation.getRequestBody().getContent(), importedComponents);
        }
        List<Parameter> parameters = operation.getParameters();
        if (parameters != null && !parameters.isEmpty()) {
            result.put("parameters", objectMapper.valueToTree(parameters));
        }
        return result;
    }

    private Map<String, JsonNode> generateResponsesMap(io.swagger.v3.oas.models.Operation operation, JsonNode importedComponents) {
        Map<String, JsonNode> result = new HashMap<>();
        if (operation.getResponses() != null) {
            result = operation.getResponses()
                    .keySet()
                    .stream()
                    .map(responseCode -> {
                        Map responseCodeMap;
                        JsonNode responseCodeMapNode = objectMapper.createObjectNode();
                        if (operation.getResponses().get(responseCode).getContent() != null) {
                            responseCodeMap = generateContentMap(operation.getResponses().get(responseCode).getContent(), importedComponents);
                            responseCodeMapNode = objectMapper.convertValue(responseCodeMap, JsonNode.class);
                        }
                        return new MutablePair<>(responseCode, responseCodeMapNode);
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return result;
    }

    private Map<String, JsonNode> generateContentMap(Content content, JsonNode importedComponents) {
        return content.keySet()
                .stream()
                .map(mediaType -> {
                    Schema<?> schema = content.get(mediaType).getSchema();
                    if (schema == null) {
                        return new MutablePair<>(mediaType, objectMapper.createObjectNode());
                    }
                    SchemaProcessor schemaProcessor = schemaProcessorMap.getOrDefault(schema.getClass().getSimpleName(),
                            schemaProcessorMap.get(DEFAULT_SCHEMA_CLASS));

                    MutablePair<String, String> processedSchemaPair = schemaProcessor.process(schema);
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

    private void resolverSwaggerEnvironment(SpecificationGroup specificationGroup, OpenAPI importedOpenAPI) {
        if (importedOpenAPI.getServers() != null && !importedOpenAPI.getServers().isEmpty()) {
            switch (specificationGroup.getSystem().getIntegrationSystemType()) {
                case EXTERNAL:
                    if (specificationGroup.getSystem().getEnvironments().isEmpty()) {
                        for (Server server : importedOpenAPI.getServers()) {
                            String name = "Environment for " + specificationGroup.getName() + " specification group";
                            if (server.getDescription() != null) {
                                name = server.getDescription();
                            }
                            String url = getUrlWithoutPlaceHolders(server);
                            Environment environment = Environment.builder()
                                    .name(name)
                                    .address(url)
                                    .labels(new ArrayList<>())
                                    .sourceType(EnvironmentSourceType.MANUAL)
                                    .build();
                            environmentBaseService.create(environment, specificationGroup.getSystem());
                            setDefaultProperties(specificationGroup);
                        }
                    }
                    break;
                case INTERNAL:
                    Environment environment = setDefaultProperties(specificationGroup);
                    if (StringUtils.isBlank(environment.getAddress())
                            && !CollectionUtils.isEmpty(importedOpenAPI.getServers())) {
                        environment.setAddress(getUrlWithoutPlaceHolders(importedOpenAPI.getServers().get(0)));
                        environmentBaseService.update(environment);
                    }
                    break;
                case IMPLEMENTED:
                    setDefaultProperties(specificationGroup);
                    break;
            }
        }
    }

    private Environment setDefaultProperties(SpecificationGroup specificationGroup) {
        Environment environment = specificationGroup.getSystem().getEnvironments().get(0);
        environmentBaseService.setDefaultProperties(environment);
        return environment;
    }

    private String getUrlWithoutPlaceHolders(Server server) {
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
}
