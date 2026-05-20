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
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.service.parsers.SpecificationParser.SPECIFICATION_FILE_PROCESSING_ERROR;


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


    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlExportImportMapper;

    @Autowired
    public ProtocolExtractionService(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper, YAMLMapper yamlExportImportMapper) {
        this.objectMapper = objectMapper;
        this.yamlExportImportMapper = yamlExportImportMapper;
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
        for (MultipartFile file : files) {
            JsonNode jsonNode = yamlExportImportMapper.readTree(file.getInputStream());
            return getProtocolFromNode(jsonNode);
        }
        return null;
    }

    private OperationProtocol getProtocolFromJson(Collection<MultipartFile> files) throws IOException {
        for (MultipartFile file : files) {
            JsonNode jsonNode = objectMapper.readTree(file.getInputStream());
            return getProtocolFromNode(jsonNode);
        }
        return null;
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
            return OperationProtocol.fromValue(jsonNode.get(SERVERS).findValuesAsText(PROTOCOL).get(0));
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
