package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService.SpecificationInfo;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.wsdl.WsdlVersionParser;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers {@link ProtocolExtractionService#extractSpecificationInfo} surfacing the API-level
 * specificationType (the api.schema.yaml enum) and specificationVersion alongside the protocol.
 */
class ProtocolExtractionServiceTypedTest {

    private ProtocolExtractionService service;

    @BeforeEach
    void setUp() {
        WsdlVersionParser wsdlVersionParser = new WsdlVersionParser(SAXParserFactory.newDefaultInstance());
        service = new ProtocolExtractionService(new ObjectMapper(), new YAMLMapper(), wsdlVersionParser);
    }

    @Test
    void openapi30() throws IOException {
        SpecificationInfo info = extract("conformance/openapi30-orders/source.input.yaml");
        assertEquals("openapi", info.specificationType());
        assertEquals("3.0.3", info.specificationVersion());
        assertEquals(OperationProtocol.HTTP, info.protocol());
    }

    @Test
    void openapi31() throws IOException {
        SpecificationInfo info = extract("conformance/openapi31-aperture-dam/source.input.json");
        assertEquals("openapi", info.specificationType());
        assertEquals("3.1.0", info.specificationVersion());
        assertEquals(OperationProtocol.HTTP, info.protocol());
    }

    @Test
    void openapi32() throws IOException {
        SpecificationInfo info = extract("conformance/openapi32-helix-observe/source.input.json");
        assertEquals("openapi", info.specificationType());
        assertEquals("3.2.0", info.specificationVersion());
        assertEquals(OperationProtocol.HTTP, info.protocol());
    }

    @Test
    void swagger20() throws IOException {
        SpecificationInfo info = extract("conformance/swagger20-inventory/source.input.json");
        assertEquals("openapi", info.specificationType());
        assertEquals("2.0", info.specificationVersion());
        assertEquals(OperationProtocol.HTTP, info.protocol());
    }

    @Test
    void asyncapi26() throws IOException {
        SpecificationInfo info = extract("conformance/asyncapi26-shipping/source.input.yaml");
        assertEquals("asyncapi", info.specificationType());
        assertEquals("2.6.0", info.specificationVersion());
        assertEquals(OperationProtocol.KAFKA, info.protocol());
    }

    @Test
    void asyncapi30() throws IOException {
        SpecificationInfo info = extract("conformance/asyncapi30-billing/source.input.yaml");
        assertEquals("asyncapi", info.specificationType());
        assertEquals("3.0.0", info.specificationVersion());
        assertEquals(OperationProtocol.AMQP, info.protocol());
    }

    @Test
    void graphql() throws IOException {
        SpecificationInfo info = extract("conformance/graphql-catalog/source.input.graphql");
        assertEquals("graphql", info.specificationType());
        assertNull(info.specificationVersion());
        assertEquals(OperationProtocol.GRAPHQL, info.protocol());
    }

    @Test
    void protobuf() throws IOException {
        SpecificationInfo info = extract("conformance/grpc-payments/source.input.proto");
        assertEquals("protobuf", info.specificationType());
        assertNull(info.specificationVersion());
        assertEquals(OperationProtocol.GRPC, info.protocol());
    }

    @Test
    void wsdl() throws IOException {
        SpecificationInfo info = extract("conformance/wsdl-hello-service/source.input.wsdl");
        assertEquals("wsdl", info.specificationType());
        assertEquals("1.1", info.specificationVersion());
        assertEquals(OperationProtocol.SOAP, info.protocol());
    }

    private SpecificationInfo extract(String resource) throws IOException {
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        MockMultipartFile file = new MockMultipartFile("spec", fileName, null, readResourceBytes(resource));
        Collection<MultipartFile> files = List.of(file);
        return service.extractSpecificationInfo(files);
    }

    private byte[] readResourceBytes(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return is.readAllBytes();
        }
    }
}
