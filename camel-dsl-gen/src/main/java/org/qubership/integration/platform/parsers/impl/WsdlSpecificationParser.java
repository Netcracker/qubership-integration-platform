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
import org.apache.woden.WSDLException;
import org.apache.woden.WSDLFactory;
import org.apache.woden.WSDLReader;
import org.apache.woden.internal.resolver.SimpleURIResolver;
import org.apache.woden.wsdl20.BindingOperation;
import org.apache.woden.wsdl20.Description;
import org.apache.woden.wsdl20.Endpoint;
import org.apache.woden.wsdl20.xml.DescriptionElement;
import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedEnvironmentImpl;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.ParsedOperationImpl;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;
import org.qubership.integration.platform.parsers.model.ParsedSystemModelImpl;
import org.qubership.integration.platform.parsers.model.wsdl.WsdlEndpoint;
import org.qubership.integration.platform.parsers.model.wsdl.WsdlParseResult;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersion;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.nonNull;

/**
 * Parses a WSDL specification into a persistence-free system model.
 *
 * <p>The parser detects the WSDL version, reads the definition with the matching library
 * (soa-model for WSDL 1.1, Apache Woden for WSDL 2.0), and produces the binding operations plus the
 * service endpoints the WSDL declares. It performs no environment side effect and touches no catalog
 * storage: WSDL 1.1 resolves imports in memory from the supplied sources, and WSDL 2.0 materializes
 * the sources into a private temporary directory that the parser deletes before returning. The
 * catalog wrapper reads the returned endpoints and registers the owning system's environments.
 */
@Slf4j
public class WsdlSpecificationParser {
    private static final String POST_VERB_NAME = "POST";
    private static final String DEFAULT_PATH = "";

    private final WsdlVersionParser wsdlVersionParser;

    public WsdlSpecificationParser(WsdlVersionParser wsdlVersionParser) {
        this.wsdlVersionParser = wsdlVersionParser;
    }

    /**
     * Parses the sources into operations and endpoints.
     *
     * @param sources all sources that make up the specification, including any imported files
     * @param mainSource the root source to parse; must be an element of {@code sources}
     * @return the parsed operations and the declared service endpoints
     * @throws SpecificationParserException if the sources cannot be parsed
     */
    public WsdlParseResult parse(Collection<SpecificationSource> sources, SpecificationSource mainSource) {
        WsdlVersion wsdlVersion = wsdlVersionParser.getWSDLVersion(mainSource.getSource());
        if (WsdlVersion.WSDL_2.equals(wsdlVersion)) {
            return parseWsdlV2(sources, mainSource);
        }
        return parseWsdlV1(sources, mainSource);
    }

    private WsdlParseResult parseWsdlV1(Collection<SpecificationSource> sources, SpecificationSource mainSource) {
        try {
            WSDLParser parser = new WSDLParser();
            parser.setResourceResolver(buildResourceResolver(sources));

            WSDLParserContext wsdlParserContext = new WSDLParserContext();
            wsdlParserContext.setInput(new ByteArrayInputStream(mainSource.getSource().getBytes(StandardCharsets.UTF_8)));

            Definitions definitions = parser.parse(wsdlParserContext);
            List<WsdlEndpoint> endpoints = collectSOAEndpoints(definitions);
            ParsedSystemModel systemModel = toSystemModel(generateSOAOperationsList(definitions), endpoints);
            return new WsdlParseResult(systemModel, endpoints);
        } catch (WrongGrammarException e) {
            String location = Arrays.stream(e.getLocation().toString().split("\n")).filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            throw new SpecificationParserException(String.format("%s: %s", location, e.getMessage()));
        } catch (ResourceDownloadException e) {
            throw new SpecificationParserException(
                    String.format("Failed to get %s: %s", e.getUrl(), e.getRootCause().getMessage()));
        }
    }

    private WsdlParseResult parseWsdlV2(Collection<SpecificationSource> sources, SpecificationSource mainSource) {
        Path workingDir = createWorkingDirectory();
        try {
            Map<SpecificationSource, URI> sourceUriMap = materializeSources(sources, workingDir);

            WSDLFactory factory = WSDLFactory.newInstance();
            WSDLReader reader = factory.newWSDLReader();
            reader.setFeature(WSDLReader.FEATURE_VALIDATION, true);

            SimpleURIResolver simpleURIResolver = new SimpleURIResolver();
            reader.setURIResolver(uri -> "file".equals(uri.getScheme())
                    ? simpleURIResolver.resolveURI(uri)
                    : resolveSource(uri.getSchemeSpecificPart(), sources)
                            .map(sourceUriMap::get)
                            .orElse(simpleURIResolver.resolveURI(uri)));

            DescriptionElement descElem = (DescriptionElement) reader.readWSDL(sourceUriMap.get(mainSource).toString());
            Description description = descElem.toComponent();

            List<WsdlEndpoint> endpoints = collectWoodenEndpoints(description);
            ParsedSystemModel systemModel = toSystemModel(generateWoodenOperationsList(description), endpoints);
            return new WsdlParseResult(systemModel, endpoints);
        } catch (WSDLException e) {
            throw new SpecificationParserException(e.getMessage(), e);
        } finally {
            deleteRecursively(workingDir);
        }
    }

    private Map<SpecificationSource, URI> materializeSources(Collection<SpecificationSource> sources, Path workingDir) {
        Map<SpecificationSource, URI> sourceUriMap = new HashMap<>();
        int index = 0;
        for (SpecificationSource source : sources) {
            String fileName = StringUtils.isEmpty(source.getName()) ? "source-" + index + ".wsdl" : source.getName();
            Path filePath = workingDir.resolve(fileName).normalize();
            try {
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, source.getSource().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write WSDL source to a temporary file", e);
            }
            sourceUriMap.put(source, filePath.toUri());
            index++;
        }
        return sourceUriMap;
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
                .map(source -> new ByteArrayInputStream(source.getSource().getBytes(StandardCharsets.UTF_8)));
    }

    private ParsedSystemModel toSystemModel(List<ParsedOperation> operations, List<WsdlEndpoint> endpoints) {
        return ParsedSystemModelImpl.builder()
                .operations(operations)
                .environments(toParsedEnvironments(endpoints))
                .build();
    }

    private List<ParsedEnvironment> toParsedEnvironments(List<WsdlEndpoint> endpoints) {
        return endpoints.stream()
                .map(endpoint -> (ParsedEnvironment) ParsedEnvironmentImpl.builder()
                        .name(endpoint.name())
                        .address(endpoint.address())
                        .build())
                .collect(Collectors.toList());
    }

    private List<ParsedOperation> generateSOAOperationsList(Definitions definitions) {
        return definitions
                .getServices()
                .stream()
                .flatMap(service -> service.getPorts().stream())
                .flatMap(port -> port.getBinding().getOperations().stream())
                .map(bindingOperation -> (ParsedOperation) ParsedOperationImpl.builder()
                        .name(bindingOperation.getName())
                        .method(POST_VERB_NAME)
                        .path(DEFAULT_PATH)
                        .build()
                )
                .collect(Collectors.toList());
    }

    private List<ParsedOperation> generateWoodenOperationsList(Description description) {
        return Arrays.stream(description.getServices())
                .flatMap(service -> Arrays.stream(service.getEndpoints()))
                .map(Endpoint::getBinding)
                .flatMap(binding -> Arrays.stream(binding.getBindingOperations()))
                .map(BindingOperation::toElement)
                .map(bindingOperationElement -> (ParsedOperation) ParsedOperationImpl.builder()
                        .name(bindingOperationElement.getRef().getLocalPart())
                        .method(POST_VERB_NAME)
                        .path(DEFAULT_PATH)
                        .build()
                )
                .collect(Collectors.toList());
    }

    private List<WsdlEndpoint> collectSOAEndpoints(Definitions definitions) {
        return definitions.getServices()
                .stream()
                .flatMap(service -> service.getPorts().stream())
                .map(port -> new WsdlEndpoint(
                        port.getName(),
                        port.getAddress() == null ? null : port.getAddress().getLocation()))
                .collect(Collectors.toList());
    }

    private List<WsdlEndpoint> collectWoodenEndpoints(Description description) {
        return Arrays.stream(description.getServices())
                .flatMap(service -> Arrays.stream(service.getEndpoints()))
                .map(endpoint -> new WsdlEndpoint(
                        endpoint.getName() == null ? null : endpoint.getName().toString(),
                        endpoint.getAddress() == null ? null : endpoint.getAddress().toString()))
                .collect(Collectors.toList());
    }

    private Path createWorkingDirectory() {
        try {
            return Files.createTempDirectory("wsdl-import-");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a temporary directory for WSDL parsing", e);
        }
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to delete temporary WSDL file {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up temporary WSDL directory {}", directory, e);
        }
    }
}
