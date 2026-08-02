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

import com.predic8.schema.Import;
import com.predic8.schema.Include;
import com.predic8.soamodel.WrongGrammarException;
import com.predic8.wsdl.Definitions;
import com.predic8.wsdl.WSDLParser;
import com.predic8.wsdl.WSDLParserContext;
import com.predic8.xml.util.ExternalResolver;
import com.predic8.xml.util.ResourceDownloadException;
import com.predic8.xml.util.ResourceResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.woden.WSDLException;
import org.apache.woden.WSDLFactory;
import org.apache.woden.WSDLReader;
import org.apache.woden.internal.resolver.SimpleURIResolver;
import org.apache.woden.wsdl20.Description;
import org.apache.woden.wsdl20.Endpoint;
import org.apache.woden.wsdl20.xml.DescriptionElement;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarIdException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.EnvironmentMapper;
import org.qubership.integration.platform.runtime.catalog.model.system.WsdlVersion;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentBaseService;
import org.qubership.integration.platform.runtime.catalog.service.FilesStorageService;
import org.qubership.integration.platform.runtime.catalog.service.parsers.Parser;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.wsdl.WsdlVersionParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType.EXTERNAL;


@Service
@Slf4j
@Parser("soap")
public class WSDLSpecificationParser implements SpecificationParser {
    private static final String POST_VERB_NAME = "POST";
    private static final String DEFAULT_PATH = "";

    private final SystemModelRepository systemModelRepository;
    private final EnvironmentBaseService environmentBaseService;
    private final EnvironmentMapper environmentMapper;
    private final WsdlVersionParser wsdlVersionParser;
    private final ParserUtils parserUtils;
    private final FilesStorageService storageService;

    @Autowired
    public WSDLSpecificationParser(
            SystemModelRepository systemModelRepository,
            EnvironmentBaseService environmentBaseService,
            EnvironmentMapper environmentMapper,
            WsdlVersionParser wsdlVersionParser,
            ParserUtils parserUtils,
            FilesStorageService storageService
    ) {
        this.systemModelRepository = systemModelRepository;
        this.environmentBaseService = environmentBaseService;
        this.environmentMapper = environmentMapper;
        this.wsdlVersionParser = wsdlVersionParser;
        this.parserUtils = parserUtils;
        this.storageService = storageService;
    }

    @Override
    public SystemModel enrichSpecificationGroup(
            ApiGroup group,
            Collection<SpecificationSource> sources,
            Set<String> oldSystemModelsIds, boolean isDiscovered,
            boolean withSchemas,
            Consumer<String> messageHandler
    ) {
        try {
            String systemModelName = parserUtils.defineVersionName(group, group);
            String systemModelId = buildId(group.getId(), systemModelName);
            SystemModel systemModel = SystemModel.builder().id(systemModelId).build();

            checkSpecId(oldSystemModelsIds, systemModelId);

            List<Operation> operationList = getParsedOperations(group, sources);

            systemModel = systemModelRepository.save(systemModel);
            systemModel.setName(systemModelName);
            systemModel.setVersion(parserUtils.defineVersion(group, group));

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

    /**
     * Reparses a WSDL main source into typed operations keyed by operation name, so the backfill can recover
     * the protocol and binding of pre-migration rows. Reuses the same V1/V2 branch and protocol/binding
     * formats as import, keeping backfilled and freshly imported rows in agreement.
     */
    public Map<String, WsdlOperation> reparseTypedOperations(String mainSource) {
        ApiGroup group = ApiGroup.builder().build();
        group.setId(UUID.randomUUID().toString());
        SpecificationSource source = new SpecificationSource();
        source.setSource(mainSource);
        source.setMainSource(true);
        return getParsedOperations(group, List.of(source)).stream()
                .filter(operation -> operation.getTyped() instanceof WsdlOperation)
                .collect(Collectors.toMap(
                        Operation::getName,
                        operation -> (WsdlOperation) operation.getTyped(),
                        (first, second) -> first));
    }

    private List<Operation> getParsedOperations(
            ApiGroup specificationGroup,
            Collection<SpecificationSource> sources
    ) throws SpecificationImportException {
        SpecificationSource mainSource = getMainSource(sources);
        WsdlVersion wsdlVersion = wsdlVersionParser.getWSDLVersion(mainSource.getSource());
        if (WsdlVersion.WSDL_2.equals(wsdlVersion)) {
            return extractOperationsFromWsdlV2(specificationGroup, sources, mainSource);
        } else {
            return extractOperationsFromWsdlV1(specificationGroup, sources, mainSource);
        }
    }

    /**
     * Retrieves the main WSDL specification source from the provided collection.
     * <p>
     * This method filters the collection for sources explicitly marked as the main source
     * using {@code isMainSource()}. If no such source is found, an exception is thrown.
     *
     * @param sources the collection of WSDL specification sources to evaluate
     * @return the main {@link SpecificationSource} marked as primary
     * @throws SpecificationImportException if no main source could be determined
     */
    private SpecificationSource getMainSource(Collection<SpecificationSource> sources) {
        return sources.stream()
                .filter(SpecificationSource::isMainSource)
                .findFirst()
                .orElseThrow(() -> new SpecificationImportException("Couldn't determine main specification source"));
    }

    private List<Operation> extractOperationsFromWsdlV2(
            ApiGroup specificationGroup,
            Collection<SpecificationSource> sources,
            SpecificationSource mainSource
    ) {
        try {
            Map<SpecificationSource, String> sourceFileMap = sources.stream().collect(Collectors.toMap(
                    Function.identity(),
                    source -> "file://" + storageService.save(
                            Paths.get(
                                    specificationGroup.getId(),
                                    StringUtils.isEmpty(source.getName()) ? source.getId() : source.getName()
                            ).toString(),
                            source.getSource().getBytes())
            ));
            WSDLFactory factory = WSDLFactory.newInstance();
            WSDLReader reader = factory.newWSDLReader();

            reader.setFeature(WSDLReader.FEATURE_VALIDATION, true);
            SimpleURIResolver simpleURIResolver = new SimpleURIResolver();
            reader.setURIResolver(uri -> "file".equals(uri.getScheme())
                    ? simpleURIResolver.resolveURI(uri)
                    : resolveSource(uri.getSchemeSpecificPart(), sources)
                    .map(source -> {
                        try {
                            return new URI(sourceFileMap.get(source));
                        } catch (URISyntaxException ignored) {
                            return null;
                        }
                    }).orElse(simpleURIResolver.resolveURI(uri)));
            DescriptionElement descElem = (DescriptionElement) reader.readWSDL(sourceFileMap.get(mainSource));
            Description description = descElem.toComponent();
            setUpWoodenEnvironment(specificationGroup, description);

            return generateWoodenOperationsList(description);
        } catch (WSDLException e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            storageService.deleteDirectory(new File(specificationGroup.getId()));
        }
    }

    private List<Operation> extractOperationsFromWsdlV1(
            ApiGroup specificationGroup,
            Collection<SpecificationSource> sources,
            SpecificationSource mainSource
    ) {
        try {

            WSDLParser parser = new WSDLParser();
            parser.setResourceResolver(buildResourceResolver(sources));

            WSDLParserContext wsdlParserContext = new WSDLParserContext();
            wsdlParserContext.setInput(new ByteArrayInputStream(mainSource.getSource().getBytes()));

            Definitions def = parser.parse(wsdlParserContext);
            setUpSOAEnvironment(specificationGroup, def);

            return generateSOAOperationsList(def);
        } catch (WrongGrammarException e) {
            String location = Arrays.stream(e.getLocation().toString().split("\n")).filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            String message = String.format("%s: %s", location, e.getMessage());
            throw new RuntimeException(message);
        } catch (ResourceDownloadException e) {
            String message = String.format("Failed to get %s: %s", e.getUrl(), e.getRootCause().getMessage());
            throw new RuntimeException(message);
        }
    }

    private ResourceResolver buildResourceResolver(Collection<SpecificationSource> sources) {
        return new ExternalResolver() {
            @Override
            public Object resolve(Object input, Object baseDir) {
                    String location = null;
                if (input instanceof Import imp) {
                    location = imp.getSchemaLocation();
                } else if (input instanceof com.predic8.wsdl.Import imp) {
                    location = imp.getLocation();
                } else if (input instanceof Include inc) {
                    location = inc.getSchemaLocation();
                } else if (input instanceof File file) {
                    location = file.getPath();
                } else if (input instanceof String s) {
                    location = s;
                }
                return Optional.ofNullable(location)
                        .<Object>flatMap(l -> resolveLocation(l, sources))
                        .orElseGet(() -> super.resolve(input, baseDir));
            }
        };
    }

    private Optional<SpecificationSource> resolveSource(String location, Collection<SpecificationSource> sources) {
        return sources.stream()
                .sorted(Comparator.comparing((SpecificationSource source) ->
                        Optional.ofNullable(source.getName()).map(String::length).orElse(0)).reversed())
                .filter(source -> nonNull(source.getName()) && location.endsWith(source.getName()))
                .findFirst();
    }

    private Optional<? extends InputStream> resolveLocation(String location, Collection<SpecificationSource> sources) {
        return resolveSource(location, sources)
                .map(source -> new ByteArrayInputStream(source.getSource().getBytes()));
    }

    private List<Operation> generateSOAOperationsList(Definitions definitions) {
        return definitions
                .getServices()
                .stream()
                .flatMap(service -> service.getPorts().stream())
                .flatMap(port -> port.getBinding().getOperations().stream())
                .map(bindingOperation -> {
                    Operation operation = Operation.builder()
                            .name(bindingOperation.getName())
                            .method(POST_VERB_NAME)
                            .path(DEFAULT_PATH)
                            .build();
                    // Not via the builder: passing typed into it skips method/path derivation.
                    operation.setTyped(soapV1TypedOperation(bindingOperation.getBinding()));
                    return operation;
                })
                .collect(Collectors.toList());
    }

    private List<Operation> generateWoodenOperationsList(Description description) {
        return Arrays.stream(description.getServices())
                .flatMap(service -> Arrays.stream(service.getEndpoints()))
                .map(Endpoint::getBinding)
                .flatMap(binding -> Arrays.stream(binding.getBindingOperations())
                        .map(bindingOperation -> {
                            Operation operation = Operation.builder()
                                    .name(bindingOperation.toElement().getRef().getLocalPart())
                                    .method(POST_VERB_NAME)
                                    .path(DEFAULT_PATH)
                                    .build();
                            // Not via the builder: passing typed into it skips method/path derivation.
                            operation.setTyped(soapV2TypedOperation(binding));
                            return operation;
                        }))
                .collect(Collectors.toList());
    }

    // WSDL 1.1 (predic8): protocol is the SOAP11/SOAP12/HTTP token, binding is the binding name.
    private static WsdlOperation soapV1TypedOperation(com.predic8.wsdl.Binding binding) {
        if (binding == null) {
            return new WsdlOperation(null, null);
        }
        Object protocol = binding.getProtocol();
        return new WsdlOperation(normalizeProtocol(protocol == null ? null : protocol.toString()), binding.getName());
    }

    // WSDL 2.0 (woden): protocol is the binding type URI, binding is the binding QName local part.
    private static WsdlOperation soapV2TypedOperation(org.apache.woden.wsdl20.Binding binding) {
        if (binding == null) {
            return new WsdlOperation(null, null);
        }
        URI type = binding.getType();
        QName name = binding.getName();
        return new WsdlOperation(
                normalizeProtocol(type == null ? null : type.toString()),
                name == null ? null : name.getLocalPart());
    }

    // Fold a raw WSDL binding token or type URI to the schema's SOAP/HTTP enum; unknown or null passes through.
    private static String normalizeProtocol(String raw) {
        if (raw == null) {
            return null;
        }
        if (StringUtils.containsIgnoreCase(raw, "soap")) {
            return "SOAP";
        }
        if (StringUtils.containsIgnoreCase(raw, "http")) {
            return "HTTP";
        }
        return raw;
    }

    private void setUpSOAEnvironment(ApiGroup specificationGroup, Definitions definitions) {
        if (specificationGroup.getSystem() != null) {
            if (EXTERNAL.equals(specificationGroup.getSystem().getIntegrationSystemType())) {
                definitions.getServices()
                        .stream()
                        .flatMap(service -> service.getPorts().stream())
                        .forEach(port -> addEnvironment(specificationGroup, port.getName(), port.getAddress().getLocation()));
            }
        }
    }

    private void setUpWoodenEnvironment(ApiGroup specificationGroup, Description description) {
        if (specificationGroup.getSystem() != null) {
            if (EXTERNAL.equals(specificationGroup.getSystem().getIntegrationSystemType())) {
                Arrays.stream(description.getServices())
                        .flatMap(service -> Arrays.stream(service.getEndpoints()))
                        .forEach(endpoint -> addEnvironment(specificationGroup, endpoint.getName().toString(), endpoint.getAddress().toString()));

            }
        }
    }

    private void addEnvironment(ApiGroup specificationGroup, String envName, String envURL) {
        UrlValidator urlValidator = new UrlValidator();
        if (urlValidator.isValid(envURL)) {
            EnvironmentRequestDTO requestDTO = new EnvironmentRequestDTO();
            requestDTO.setName(envName);
            requestDTO.setAddress(envURL);
            Environment environment = environmentMapper.toEnvironment(requestDTO);
            environmentBaseService.create(environment, specificationGroup.getSystem());
        }
    }
}
