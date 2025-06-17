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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.links.Link;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import jakarta.persistence.EntityNotFoundException;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ApiSpecificationExportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.HttpTriggerMethodsNotSpecified;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.WrongChainElementTypeException;
import org.qubership.integration.platform.runtime.catalog.model.ElementRoute;
import org.qubership.integration.platform.runtime.catalog.model.apispec.ApiSpecificationFormat;
import org.qubership.integration.platform.runtime.catalog.model.apispec.ApiSpecificationType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.util.TriggerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;
import static org.qubership.integration.platform.runtime.catalog.util.TriggerUtils.*;

@Slf4j
@Transactional
@Service
public class ApiSpecificationExportService {
    private static final String INTERNAL_ROUTES_BASE_PATH = "/routes";
    private static final String OPERATION_WITH_ID_NOT_FOUND_MESSAGE = "Can't find operation with id ";

    @Data
    @Builder
    private static class SpecificationBuildParameters {
        Collection<ChainElement> elements;
        boolean externalRoutes;
    }

    private final String externalRoutesBasePath;
    private final ElementRepository elementRepository;
    private final OperationRepository operationRepository;
    private final SystemModelService systemModelService;

    @Autowired
    public ApiSpecificationExportService(
            @Value("${qip.chains.external-routes.base-path:/qip-routes}") String externalRoutesBasePath,
            ElementRepository elementRepository,
            OperationRepository operationRepository,
            SystemModelService systemModelService
    ) {
        this.externalRoutesBasePath = !externalRoutesBasePath.startsWith("/")
                ? "/" + externalRoutesBasePath
                : externalRoutesBasePath;
        this.elementRepository = elementRepository;
        this.operationRepository = operationRepository;
        this.systemModelService = systemModelService;
    }

    public Pair<String, byte[]> exportApiSpecification(
            Collection<String> deploymentIds,
            Collection<String> snapshotIds,
            Collection<String> chainIds,
            Collection<String> httpTriggerIds,
            boolean externalRoutes,
            ApiSpecificationType apiSpecificationType,
            ApiSpecificationFormat apiSpecificationFormat
    ) {
        Collection<ChainElement> elements = getTriggerElements(deploymentIds, snapshotIds, chainIds, httpTriggerIds, externalRoutes,
                apiSpecificationType);
        SpecificationBuildParameters buildParameters = SpecificationBuildParameters.builder()
                .elements(elements).externalRoutes(externalRoutes).build();
        Object apiSpecification = buildApiSpecification(apiSpecificationType, buildParameters);
        String specificationText = serializeApiSpecification(apiSpecification, apiSpecificationFormat);
        String specificationFileName = getSpecificationFileName(apiSpecificationType, apiSpecificationFormat);
        return Pair.of(specificationFileName, specificationText.getBytes());
    }

    private Collection<ChainElement> getTriggerElements(
            Collection<String> deploymentIds,
            Collection<String> snapshotIds,
            Collection<String> chainIds,
            Collection<String> httpTriggerIds,
            boolean externalRoutes,
            ApiSpecificationType apiSpecificationType
    ) {
        Collection<String> triggerTypes = getTriggerTypes(apiSpecificationType);
        Predicate<ChainElement> elementFilterPredicate =
                getElementFilterPredicate(apiSpecificationType, externalRoutes);

        if (!chainIds.isEmpty()) {
            elementFilterPredicate = elementFilterPredicate.and(element -> httpTriggerIds.contains(element.getId()));
        }

        Stream<ChainElement> elementStream = (deploymentIds.isEmpty() && snapshotIds.isEmpty() && chainIds.isEmpty())
                ? elementRepository.findAllDeployedElementsByTypes(triggerTypes).stream()
                : Stream.of(
                elementRepository.findElementsByTypesAndDeployments(triggerTypes, deploymentIds),
                elementRepository.findElementsByTypesAndSnapshots(triggerTypes, snapshotIds),
                elementRepository.findElementsByTypesAndChains(triggerTypes, chainIds)
        ).flatMap(Collection::stream);
        return elementStream.filter(elementFilterPredicate).collect(Collectors.toList());
    }

    private Collection<String> getTriggerTypes(ApiSpecificationType apiSpecificationType) {
        switch (apiSpecificationType) {
            case AsyncAPI: return getAsyncTriggerTypeNames();
            case OpenAPI: return Collections.singletonList(getHttpTriggerTypeName());
            default: return Collections.emptyList();
        }
    }

    private Predicate<ChainElement> getElementFilterPredicate(
            ApiSpecificationType apiSpecificationType,
            boolean externalRoutes
    ) {
        switch (apiSpecificationType) {
            case OpenAPI: return element -> !externalRoutes || getHttpTriggerRoute(element).isExternal();
            case AsyncAPI:
            default: return element -> true;
        }
    }

    private String getSpecificationFileName(ApiSpecificationType apiSpecificationType, ApiSpecificationFormat format) {
        DateFormat dateFormat = new SimpleDateFormat(DATE_TIME_FORMAT_PATTERN);
        String formatPostfix = getFileNamePostfix(format);
        return EXPORT_FILE_NAME_PREFIX + apiSpecificationType.name().toLowerCase()
                + API_SPECIFICATION_SUFFIX + dateFormat.format(new Date()) + formatPostfix;
    }

    private String getFileNamePostfix(ApiSpecificationFormat format) {
        switch (format) {
            case YAML: return YAML_FILE_NAME_POSTFIX;
            case JSON: return JSON_FILE_NAME_POSTFIX;
            default: return "";
        }
    }

    private Object buildApiSpecification(
            ApiSpecificationType apiSpecificationType,
            SpecificationBuildParameters buildParameters
    ) {
        return getSpecificationBuilder(apiSpecificationType).apply(buildParameters);
    }

    private Function<SpecificationBuildParameters, Object> getSpecificationBuilder(ApiSpecificationType apiSpecificationType) {
        switch (apiSpecificationType) {
            case OpenAPI: return this::buildOpenApiSpecification;
            case AsyncAPI: return this::buildAsyncApiSpecification;
            default:
                throw new ApiSpecificationExportException("Unsupported specification type: " + apiSpecificationType.name());
        }
    }

    private String serializeApiSpecification(Object specification, ApiSpecificationFormat format) {
        ObjectMapper mapper = getSpecificationMapper(format);
        try {
            return mapper.writeValueAsString(specification);
        } catch (JsonProcessingException exception) {
            throw new ApiSpecificationExportException("Failed to export API specification", exception);
        }
    }

    private ObjectMapper getSpecificationMapper(ApiSpecificationFormat format) {
        switch (format) {
            case YAML: return Yaml.mapper();
            case JSON: return Json.mapper();
            default:
                throw new ApiSpecificationExportException("Unsupported specification format: " + format.name());
        }
    }

    private Object buildOpenApiSpecification(SpecificationBuildParameters buildParameters) {
        return new OpenAPI()
                .info(buildSpecificationInfo(buildParameters.getElements()))
                .paths(buildPaths(buildParameters.getElements()))
                .servers(buildServers(buildParameters.isExternalRoutes()))
                .components(buildComponents(buildParameters.getElements()));
    }

    private List<Server> buildServers(boolean externalRoutes) {
        Server server = new Server();
        String basePath = externalRoutes ? externalRoutesBasePath : INTERNAL_ROUTES_BASE_PATH;
        server.url("{basePath}").variables(new ServerVariables()
                .addServerVariable("basePath", new ServerVariable()._default(basePath)));
        return Collections.singletonList(server);
    }

    private String buildAsyncApiSpecification(SpecificationBuildParameters buildParameters) {
        // TODO
        throw new ApiSpecificationExportException("OpenAPI specification export not implemented yet");
    }

    private Info buildSpecificationInfo(Collection<ChainElement> elements) {
        return new Info()
                .title(buildConfigurationTitle())
                .description(buildConfigurationDescription(elements))
                .version(buildConfigurationVersion());
    }

    private String buildConfigurationTitle() {
        return API_SPECIFICATION_TITLE;
    }

    private String buildConfigurationVersion() {
        DateFormat dateFormat = new SimpleDateFormat(DATE_TIME_FORMAT_PATTERN);
        return API_SPECIFICATION_VERSION_PREFIX + dateFormat.format(new Date());
    }

    private String buildConfigurationDescription(Collection<ChainElement> elements) {
        StringBuilder sb = new StringBuilder();
        elements.forEach(element -> {
            sb.append("* ").append(getElementChain(element).getName()).append(" - ").append(element.getName());
            if (nonNull(element.getSnapshot())) {
                sb.append(" (").append(element.getSnapshot().getName()).append(")");
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private Chain getElementChain(ChainElement element) {
        Chain chain = element.getChain();
        return isNull(chain) ? element.getSnapshot().getChain() : chain;
    }

    private Paths buildPaths(Collection<ChainElement> elements) {
        Paths paths = new Paths();
        // TODO process operations in parallel manner
        for (ChainElement element : elements) {
            verifyElement(element);
            ElementRoute route = getHttpTriggerRoute(element);
            PathItem pathItem = paths.computeIfAbsent("/" + route.getPath(), path -> new PathItem());
            for (HttpMethod method : route.getMethods()) {
                pathItem.operation(toOpenApiHttpMethod(method), buildOperation(element, route, method));
            }
        }
        return paths;
    }

    private void verifyElement(ChainElement element) {
        if (!TriggerUtils.isHttpTrigger(element)) {
            throw new WrongChainElementTypeException(element, Collections.singletonList(getHttpTriggerTypeName()));
        }
        if (!TriggerUtils.areHttpTriggerMethodsSpecified(element)) {
            throw new HttpTriggerMethodsNotSpecified(element);
        }
    }

    private Components buildComponents(Collection<ChainElement> elements) {
        Stream<Components> implementedServiceTriggerComponents = elements.stream()
                .filter(TriggerUtils::isImplementedServiceTrigger)
                .map(TriggerUtils::getImplementedServiceTriggerSpecificationId)
                .distinct()
                .parallel()
                .map(specificationId -> {
                    Components components = getSpecification(specificationId).getComponents();
                    if (isNull(components)) {
                        components = new Components();
                    }
                    updateReferencesForComponents(components, ref -> addSuffixToRef(ref, specificationId));
                    updateIdentifiersForComponents(components, specificationId);
                    return components;
                });
        Stream<Components> customUriTriggerComponents = elements.stream()
                .filter(TriggerUtils::isCustomUriHttpTrigger)
                .parallel()
                .map(element -> {
                    String validationSchemaText = getHttpTriggerValidationSchema(element);
                    if (StringUtils.isBlank(validationSchemaText)) {
                        return new Components();
                    }
                    Components components = buildComponentsFromValidationSchema(validationSchemaText);
                    updateReferencesForComponents(components, ref -> updateSchemasRefAndAddSuffixToRef(ref, element.getId()));
                    updateIdentifiersForComponents(components, element.getId());
                    return components;
                });
        return Stream.concat(implementedServiceTriggerComponents, customUriTriggerComponents)
                .reduce(this::mergeComponents).orElse(new Components());
    }

    private String addSuffixToRef(String ref, String suffix) {
        return ref.startsWith(Components.COMPONENTS_SCHEMAS_REF) ? ref + "-" + suffix : ref;
    }

    private String updateSchemasRefAndAddSuffixToRef(String ref, String suffix) {
        return addSuffixToRef(ref.replace("#/definitions/", Components.COMPONENTS_SCHEMAS_REF), suffix);
    }

    private Components buildComponentsFromValidationSchema(String validationSchemaText) {
        ApiSpecificationFormat format = guessFormat(validationSchemaText);
        ObjectMapper mapper = getSpecificationMapper(format);
        try {
            JsonNode schemaNode = mapper.readTree(validationSchemaText);
            JsonNode definitionsNode = schemaNode.get("definitions");
            if (isNull(definitionsNode)) {
                return new Components();
            }
            Map<String, Schema> schemas = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fieldIterator = definitionsNode.fields();
            while (fieldIterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = fieldIterator.next();
                Schema schema = mapper.treeToValue(entry.getValue(), Schema.class);
                schemas.put(entry.getKey(), schema);
            }
            return new Components().schemas(schemas);
        } catch (JsonProcessingException exception) {
            throw new ApiSpecificationExportException("Failed to parse validation schema", exception);
        }
    }

    private OpenAPI getSpecification(String specificationId) {
        String specificationText = systemModelService.getMainSystemModelSource(specificationId);
        ApiSpecificationFormat format = guessFormat(specificationText);
        ObjectMapper mapper = getSpecificationMapper(format);
        try {
            return mapper.readValue(specificationText, OpenAPI.class);
        } catch (JsonProcessingException exception) {
            throw new ApiSpecificationExportException("Failed to parse specification", exception);
        }
    }

    private Components mergeComponents(Components c0, Components c1) {
        if (isNull(c0)) {
            return c1;
        }

        if (isNull(c1)) {
            return c0;
        }

        return new Components()
                .schemas(mergeMaps(c0.getSchemas(), c1.getSchemas()))
                .responses(mergeMaps(c0.getResponses(), c1.getResponses()))
                .parameters(mergeMaps(c0.getParameters(), c1.getParameters()))
                .examples(mergeMaps(c0.getExamples(), c1.getExamples()))
                .requestBodies(mergeMaps(c0.getRequestBodies(), c1.getRequestBodies()))
                .headers(mergeMaps(c0.getHeaders(), c1.getHeaders()))
                .securitySchemes(mergeMaps(c0.getSecuritySchemes(), c1.getSecuritySchemes()))
                .links(mergeMaps(c0.getLinks(), c1.getLinks()))
                .callbacks(mergeMaps(c0.getCallbacks(), c1.getCallbacks()))
                .extensions(mergeMaps(c0.getExtensions(), c1.getExtensions()));
    }

    private <T> Map<String, T> mergeMaps(Map<String, T> m0, Map<String, T> m1) {
        return Stream.of(m0, m1).filter(Objects::nonNull).map(Map::entrySet)
                .flatMap(Collection::stream).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private PathItem.HttpMethod toOpenApiHttpMethod(HttpMethod method) {
        return PathItem.HttpMethod.valueOf(method.name());
    }

    private Operation buildOperation(ChainElement element, ElementRoute route, HttpMethod method) {
        return isImplementedServiceTrigger(element)
                ? buildOperationForImplementedServiceTrigger(element)
                : buildOperationForCustomUriTrigger(element, route, method);
    }

    private Operation buildOperationForImplementedServiceTrigger(ChainElement element) {
        String modelId = getImplementedServiceTriggerSpecificationId(element);
        String operationId = getImplementedServiceTriggerOperationId(element);
        String operationSpecificationText = findOperation(operationId).getSpecification().toString();
        ApiSpecificationFormat format = guessFormat(operationSpecificationText);
        ObjectMapper mapper = getSpecificationMapper(format);
        try {
            Operation operation = mapper.readValue(operationSpecificationText, Operation.class);
            updateOperationId(operation, element);
            updateReferencesForOperation(operation, ref -> addSuffixToRef(ref, modelId));
            return operation;
        } catch (JsonProcessingException exception) {
            throw new ApiSpecificationExportException("Failed to parse operation specification", exception);
        }
    }

    private void updateOperationId(Operation operation, ChainElement element) {
        operation.setOperationId(operation.getOperationId() + "-" + element.getId());
    }

    private void updateReferencesForComponents(Components components, Function<String, String> refModifier) {
        if (isNull(components)) {
            return;
        }
        updateReferencesForSchemas(components.getSchemas(), refModifier);
        updateReferencesForApiResponses(components.getResponses(), refModifier);

        Map<String, Parameter> parameters = components.getParameters();
        if (nonNull(parameters)) {
            updateReferencesForParameters(parameters.values(), refModifier);
        }

        updateReferencesForExamples(components.getExamples(), refModifier);
        updateReferencesForRequestBodies(components.getRequestBodies(), refModifier);
        updateReferencesForHeaders(components.getHeaders(), refModifier);
        updateReferencesForSecuritySchemes(components.getSecuritySchemes(), refModifier);
        updateReferencesForLinks(components.getLinks(), refModifier);
        updateReferencesForCallbacks(components.getCallbacks(), refModifier);
    }

    private void updateIdentifiersForComponents(Components components, String modelId) {
        if (isNull(components)) {
            return;
        }
        updateComponentMapKeys(components.getSchemas(), modelId);
        updateComponentMapKeys(components.getResponses(), modelId);
        updateComponentMapKeys(components.getParameters(), modelId);
        updateComponentMapKeys(components.getExamples(), modelId);
        updateComponentMapKeys(components.getRequestBodies(), modelId);
        updateComponentMapKeys(components.getHeaders(), modelId);
        updateComponentMapKeys(components.getSecuritySchemes(), modelId);
        updateComponentMapKeys(components.getLinks(), modelId);
        updateComponentMapKeys(components.getCallbacks(), modelId);
    }

    private <T> void updateComponentMapKeys(Map<String, T> componentMap, String modelId) {
        if (nonNull(componentMap)) {
            Map<String, T> m = componentMap.entrySet().stream()
                    .collect(Collectors.toMap(entry -> entry.getKey() + "-" + modelId, Map.Entry::getValue));
            componentMap.clear();
            componentMap.putAll(m);
        }
    }

    private void updateReferencesForSchemas(Map<String, Schema> schemas, Function<String, String> refModifier) {
        if (nonNull(schemas)) {
            schemas.values().forEach(schema -> updateReferencesForSchema(schema, refModifier));
        }
    }

    private void updateReferencesForExamples(Map<String, Example> examples, Function<String, String> refModifier) {
        if (nonNull(examples)) {
            examples.values().stream().filter(Objects::nonNull).forEach(example -> {
                if (nonNull(example.get$ref())) {
                    example.set$ref(refModifier.apply(example.get$ref()));
                }
            });
        }
    }

    private void updateReferencesForRequestBodies(Map<String, RequestBody> requestBodies, Function<String, String> refModifier) {
        if (nonNull(requestBodies)) {
            requestBodies.values().forEach(requestBody -> updateReferencesForRequestBody(requestBody, refModifier));
        }
    }

    private void updateReferencesForSecuritySchemes(Map<String, SecurityScheme> securitySchemes, Function<String, String> refModifier) {
        if (nonNull(securitySchemes)) {
            securitySchemes.values().stream().filter(Objects::nonNull).forEach(securityScheme -> {
                if (nonNull(securityScheme.get$ref())) {
                    securityScheme.set$ref(refModifier.apply(securityScheme.get$ref()));
                }
            });
        }
    }

    private void updateReferencesForOperation(Operation operation, Function<String, String> refModifier) {
        if (isNull(operation)) {
            return;
        }

        updateReferencesForParameters(operation.getParameters(), refModifier);
        updateReferencesForRequestBody(operation.getRequestBody(), refModifier);
        updateReferencesForApiResponses(operation.getResponses(), refModifier);
        updateReferencesForCallbacks(operation.getCallbacks(), refModifier);
    }

    private void updateReferencesForCallbacks(Map<String, Callback> callbacks, Function<String, String> refModifier) {
        if (nonNull(callbacks)) {
            callbacks.values().stream().filter(Objects::nonNull).forEach(callback -> {
                if (nonNull(callback.get$ref())) {
                    callback.set$ref(refModifier.apply(callback.get$ref()));
                }
                callback.values().stream().filter(Objects::nonNull).forEach(pathItem -> {
                    if (nonNull(pathItem.get$ref())) {
                        pathItem.set$ref(refModifier.apply(pathItem.get$ref()));
                    }
                    updateReferencesForOperation(pathItem.getDelete(), refModifier);
                    updateReferencesForOperation(pathItem.getGet(), refModifier);
                    updateReferencesForOperation(pathItem.getOptions(), refModifier);
                    updateReferencesForOperation(pathItem.getPatch(), refModifier);
                    updateReferencesForOperation(pathItem.getHead(), refModifier);
                    updateReferencesForOperation(pathItem.getPatch(), refModifier);
                    updateReferencesForOperation(pathItem.getPost(), refModifier);
                    updateReferencesForOperation(pathItem.getTrace(), refModifier);
                    updateReferencesForParameters(pathItem.getParameters(), refModifier);
                });
            });
        }
    }

    private void updateReferencesForRequestBody(RequestBody requestBody, Function<String, String> refModifier) {
        if (nonNull(requestBody)) {
            if (nonNull(requestBody.get$ref())) {
                requestBody.set$ref(refModifier.apply(requestBody.get$ref()));
            }
            updateReferencesForContent(requestBody.getContent(), refModifier);
        }
    }

    private void updateReferencesForApiResponses(Map<String, ApiResponse> responses, Function<String, String> refModifier) {
        if (nonNull(responses)) {
            responses.values().stream().filter(Objects::nonNull).forEach(response -> {
                if (nonNull(response.get$ref())) {
                    response.set$ref(refModifier.apply(response.get$ref()));
                }
                updateReferencesForContent(response.getContent(), refModifier);
                updateReferencesForHeaders(response.getHeaders(), refModifier);
                updateReferencesForLinks(response.getLinks(), refModifier);
            });
        }
    }

    private void updateReferencesForLinks(Map<String, Link> links, Function<String, String> refModifier) {
        if (nonNull(links)) {
            links.values().stream().filter(Objects::nonNull).forEach(link -> {
                if (nonNull(link.get$ref())) {
                    link.set$ref(refModifier.apply(link.get$ref()));
                }
                if (nonNull(link.getOperationRef())) {
                    // TODO
                }
                if (nonNull(link.getOperationId())) {
                    // TODO
                }
                updateReferencesForHeaders(link.getHeaders(), refModifier);
            });
        }
    }

    private void updateReferencesForContent(Content content, Function<String, String> refModifier) {
        if (nonNull(content)) {
            content.values().stream().filter(Objects::nonNull).forEach(mediaType -> {
                Map<String, Encoding> encoding = mediaType.getEncoding();
                if (nonNull(encoding)) {
                    encoding.values().stream().filter(Objects::nonNull)
                            .forEach(e -> updateReferencesForHeaders(e.getHeaders(), refModifier));
                }
                updateReferencesForSchema(mediaType.getSchema(), refModifier);
            });
        }
    }

    private void updateReferencesForSchema(Schema schema, Function<String, String> refModifier) {
        if (nonNull(schema)) {
            if (nonNull(schema.get$ref())) {
                schema.set$ref(refModifier.apply(schema.get$ref()));
            }
            updateReferencesForSchemas(schema.getProperties(), refModifier);
            if (schema instanceof ArraySchema) {
                updateReferencesForSchema(((ArraySchema) schema).getItems(), refModifier);
            }
            if (schema instanceof ComposedSchema) {
                ComposedSchema composedSchema = (ComposedSchema) schema;
                Stream.of(composedSchema.getAllOf(), composedSchema.getOneOf(), composedSchema.getAnyOf())
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .forEach(s -> updateReferencesForSchema(s, refModifier));
            }
        }
    }

    private void updateReferencesForParameters(Collection<Parameter> parameters, Function<String, String> refModifier) {
        if (nonNull(parameters)) {
            parameters.forEach(parameter -> {
                if (nonNull(parameter.get$ref())) {
                    parameter.set$ref(refModifier.apply(parameter.get$ref()));
                }
            });
        }
    }

    private void updateReferencesForHeaders(Map<String, Header> headers, Function<String, String> refModifier) {
        if (nonNull(headers)) {
            headers.values().stream().filter(Objects::nonNull).forEach(header -> {
                if (nonNull(header.get$ref())) {
                    header.set$ref(refModifier.apply(header.get$ref()));
                }
            });
        }
    }

    private ApiSpecificationFormat guessFormat(String text) {
        return text.trim().startsWith("{") ? ApiSpecificationFormat.JSON : ApiSpecificationFormat.YAML;
    }

    private Operation buildOperationForCustomUriTrigger(ChainElement element, ElementRoute route, HttpMethod method) {
        return new Operation()
                .operationId(buildOperationId(element, route, method))
                .summary(buildOperationSummary(element))
                .description(element.getDescription())
                .parameters(buildCustomUriTriggerParameters(route.getPath()))
                .requestBody(buildRequestBodyForCustomUriTrigger(element))
                .responses(buildCustomUriTriggerResponses());
    }

    private String buildOperationSummary(ChainElement element) {
        return getElementChain(element).getName() + " - " + element.getName();
    }

    private String buildOperationId(ChainElement element, ElementRoute route, HttpMethod method) {
        return method.name().toLowerCase()
                + "-" + StringUtils.strip(route.getPath().replaceAll("[^\\w\\d]+", "-").toLowerCase(), "-")
                + "-" + element.getId();
    }

    private ApiResponses buildCustomUriTriggerResponses() {
        return new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("Operation success"))
                ._default(new ApiResponse().description("Unexpected error"));
    }

    private List<Parameter> buildCustomUriTriggerParameters(String path) {
        Pattern pattern = Pattern.compile("\\{([^}]+)}");
        Matcher matcher = pattern.matcher(path);
        List<Parameter> parameters = new ArrayList<>();
        while (matcher.find()) {
            Parameter parameter = new Parameter()
                    .name(matcher.group(1))
                    .in("path")
                    .required(true)
                    .schema(new Schema<String>().type("string"));
            parameters.add(parameter);
        }
        return parameters;
    }

    private RequestBody buildRequestBodyForCustomUriTrigger(ChainElement element) {
        String validationSchemaText = getHttpTriggerValidationSchema(element);
        if (StringUtils.isBlank(validationSchemaText)) {
            return null;
        }
        Content content = buildContentFromValidationSchema(validationSchemaText);
        updateReferencesForContent(content, ref -> updateSchemasRefAndAddSuffixToRef(ref, element.getId()));
        return isNull(content) ? null : new RequestBody().content(content);
    }

    private Content buildContentFromValidationSchema(String validationSchemaText) {
        ApiSpecificationFormat format = guessFormat(validationSchemaText);
        ObjectMapper mapper = getSpecificationMapper(format);
        try {
            JsonNode schemaNode = mapper.readTree(validationSchemaText);
            Schema schema = mapper.treeToValue(schemaNode, Schema.class);
            return new Content().addMediaType("application/json", new MediaType().schema(schema));
        } catch (JsonProcessingException exception) {
            throw new ApiSpecificationExportException("Failed to parse validation schema", exception);
        }
    }

    private org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation findOperation(String operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new EntityNotFoundException(OPERATION_WITH_ID_NOT_FOUND_MESSAGE + operationId));
    }
}
