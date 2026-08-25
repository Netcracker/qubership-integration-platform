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

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersion;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.qubership.integration.platform.parsers.SpecificationParser.SPECIFICATION_FILE_PROCESSING_ERROR;


@Slf4j
@Service
public class ProtocolExtractionService {
    private static final String UNABLE_TO_DEFINE_FILE_EXTENSION = "Can't define specification file extension";
    private static final String FILE_LIST_IS_EMPTY_ERROR_MESSAGE = "File list is empty";
    private static final String INFO = "info";
    private static final String SERVERS = "servers";
    private static final String PROTOCOL = "protocol";
    private static final String XPROTOCOL = "x-protocol";
    private static final String XSD_EXTENSION = "xsd";
    private static final String WSDL_EXTENSION = "wsdl";
    private static final String PROTOBUF_EXTENSION = "proto";
    private static final String YAML_EXTENSION = "yaml";
    private static final String YML_EXTENSION = "yml";
    private static final String GRAPHQL_EXTENSION = "graphql";
    private static final String GRAPHQLS_EXTENSION = "graphqls";
    private static final String SWAGGER = "swagger";
    private static final String OPENAPI = "openapi";
    private static final String ASYNCAPI = "asyncapi";
    private static final String METAMODEL_DYNAMIC_METADATA = "dynamicMetadata";
    private static final String METAMODEL_LAYOUTS = "layouts";
    private static final String METAMODEL_STATIC_METADATA = "staticMetadata";
    private static final String METAMODEL_VALIDATIONS = "validations";

    // OperationProtocol.type values that api.schema.yaml spells differently as its specificationType enum.
    private static final String SOAP_TYPE = "soap";
    private static final String GRAPHQL_SCHEMA_TYPE = "graphqlschema";
    private static final String PROTOBUF_TYPE = "protobuf";
    private static final String SPEC_TYPE_WSDL = "wsdl";
    private static final String SPEC_TYPE_GRAPHQL = "graphql";
    private static final String WSDL_VERSION_1_1 = "1.1";
    private static final String WSDL_VERSION_2_0 = "2.0";


    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlExportImportMapper;
    private final WsdlVersionParser wsdlVersionParser;

    @Autowired
    public ProtocolExtractionService(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
                                     YAMLMapper yamlExportImportMapper,
                                     WsdlVersionParser wsdlVersionParser) {
        this.objectMapper = objectMapper;
        this.yamlExportImportMapper = yamlExportImportMapper;
        this.wsdlVersionParser = wsdlVersionParser;
    }

    /**
     * API-level specification metadata carried on {@code models}: the api.schema.yaml specificationType,
     * the specification standard version, and the resolved transport protocol.
     */
    public record SpecificationInfo(
            String specificationType,
            String specificationVersion,
            OperationProtocol protocol) {
    }

    /**
     * Resolves the protocol plus the API-level specificationType and specificationVersion from the imported
     * source files.
     */
    public SpecificationInfo extractSpecificationInfo(Collection<MultipartFile> files) {
        OperationProtocol protocol = getOperationProtocol(files);
        String version = extractSpecificationVersion(protocol, readFirstSource(files));
        return new SpecificationInfo(mapSpecificationType(protocol), version, protocol);
    }

    /**
     * Maps an {@link OperationProtocol} onto the api.schema.yaml specificationType enum. METAMODEL has no
     * counterpart and never reaches the API level, so it and any unknown type yield null.
     */
    public static String mapSpecificationType(OperationProtocol protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol.type) {
            case SWAGGER -> OPENAPI;
            case ASYNCAPI -> ASYNCAPI;
            case SOAP_TYPE -> SPEC_TYPE_WSDL;
            case GRAPHQL_SCHEMA_TYPE -> SPEC_TYPE_GRAPHQL;
            case PROTOBUF_TYPE -> PROTOBUF_TYPE;
            default -> null;
        };
    }

    /**
     * Reads the specification standard version from a root source document: the {@code openapi}/{@code swagger}
     * or {@code asyncapi} field for HTTP and AsyncAPI, the WSDL version for SOAP. GraphQL and gRPC carry no
     * such marker and yield null. A source that cannot be read degrades to null rather than failing the import.
     */
    public String extractSpecificationVersion(OperationProtocol protocol, String source) {
        if (protocol == null || source == null) {
            return null;
        }
        try {
            return switch (protocol) {
                case HTTP -> readDocumentVersion(source, SWAGGER, OPENAPI);
                case KAFKA, AMQP -> readDocumentVersion(source, ASYNCAPI);
                case SOAP -> WsdlVersion.WSDL_2.equals(wsdlVersionParser.getWSDLVersion(source))
                        ? WSDL_VERSION_2_0 : WSDL_VERSION_1_1;
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Unable to read specification version", e);
            return null;
        }
    }

    private String readDocumentVersion(String source, String... versionKeys) throws IOException {
        JsonNode root = yamlExportImportMapper.readTree(source);
        if (root == null) {
            return null;
        }
        for (String key : versionKeys) {
            JsonNode value = root.get(key);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private String readFirstSource(Collection<MultipartFile> files) {
        if (files.isEmpty()) {
            throw new SpecificationImportException(FILE_LIST_IS_EMPTY_ERROR_MESSAGE);
        }
        try {
            return new String(files.iterator().next().getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        }
    }

    public OperationProtocol getOperationProtocol(Collection<MultipartFile> files) {
        if (files.isEmpty()) {
            throw new SpecificationImportException(FILE_LIST_IS_EMPTY_ERROR_MESSAGE);
        }

        String fileExtension = FilenameUtils.getExtension(files.stream()
                .map(MultipartFile::getOriginalFilename).findFirst().orElse(""));

        if (WSDL_EXTENSION.equalsIgnoreCase(fileExtension)
                || XSD_EXTENSION.equalsIgnoreCase(fileExtension)) {
            return OperationProtocol.SOAP;
        }

        if (GRAPHQL_EXTENSION.equalsIgnoreCase(fileExtension)
                || GRAPHQLS_EXTENSION.equalsIgnoreCase(fileExtension)) {
            return OperationProtocol.GRAPHQL;
        }

        if (PROTOBUF_EXTENSION.equalsIgnoreCase(fileExtension)) {
            return OperationProtocol.GRPC;
        }

        try {
            if (YAML_EXTENSION.equalsIgnoreCase(fileExtension)
                    || YML_EXTENSION.equalsIgnoreCase(fileExtension)) {
                return getProtocolFromYaml(files);
            } else {
                return getProtocolFromJson(files);
            }
        } catch (JsonParseException e) {
            throw new SpecificationImportException(SPECIFICATION_FILE_PROCESSING_ERROR, e);
        } catch (Exception e) {
            throw new SpecificationImportException(UNABLE_TO_DEFINE_FILE_EXTENSION, e);
        }
    }

    private OperationProtocol getProtocolFromYaml(Collection<MultipartFile> files) throws IOException {
        MultipartFile file = files.iterator().next();
        JsonNode jsonNode = yamlExportImportMapper.readTree(file.getInputStream());
        return getProtocolFromNode(jsonNode);
    }

    private OperationProtocol getProtocolFromJson(Collection<MultipartFile> files) throws IOException {
        MultipartFile file = files.iterator().next();
        JsonNode jsonNode = objectMapper.readTree(file.getInputStream());
        return getProtocolFromNode(jsonNode);
    }

    private OperationProtocol getProtocolFromNode(JsonNode jsonNode) {
        if (jsonNode != null) {
            if (jsonNode.has(SWAGGER) || jsonNode.has(OPENAPI)) {
                return OperationProtocol.HTTP;
            } else if (jsonNode.has(ASYNCAPI)) {
                return getProtocolFromAsyncSpec(jsonNode);
            } else if (Stream.of(METAMODEL_DYNAMIC_METADATA, METAMODEL_STATIC_METADATA,
                    METAMODEL_LAYOUTS, METAMODEL_VALIDATIONS).allMatch(jsonNode::has)) {
                return OperationProtocol.METAMODEL;
            }
        }
        return null;
    }

    private OperationProtocol getProtocolFromAsyncSpec(JsonNode jsonNode) {
        if (jsonNode.has(SERVERS)) {
            List<String> protocols = jsonNode.get(SERVERS).findValuesAsText(PROTOCOL);
            if (!protocols.isEmpty()) {
                return OperationProtocol.fromValue(protocols.get(0));
            }
        }

        if (jsonNode.has(INFO) && jsonNode.get(INFO).has(XPROTOCOL)) {
            return OperationProtocol.fromValue(jsonNode.get(INFO).get(XPROTOCOL).asText());
        }

        return null;
    }

    public OperationProtocol getProtocol(String specificationType) {
        if (OperationProtocol.SOAP.type.equals(specificationType)) {
            return OperationProtocol.SOAP;
        } else if (OperationProtocol.HTTP.type.equals(specificationType)) {
            return OperationProtocol.HTTP;
        } else if (OperationProtocol.KAFKA.type.equals(specificationType)) {
            return OperationProtocol.KAFKA;
        } else if (OperationProtocol.GRPC.type.equals(specificationType)) {
            return OperationProtocol.GRPC;
        } else {
            return OperationProtocol.fromValue(specificationType);
        }
    }

}
