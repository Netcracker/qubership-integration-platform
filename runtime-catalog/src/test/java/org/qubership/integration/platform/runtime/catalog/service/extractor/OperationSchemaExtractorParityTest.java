package org.qubership.integration.platform.runtime.catalog.service.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.assertNodeEquals;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.expectedCases;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.findInput;

/**
 * Golden parity gate: the {@link OperationSchemaExtractor} must reproduce, from the raw source
 * alone, the {@code specification} / {@code requestSchema} / {@code responseSchemas} that import
 * once materialized. The oracle is the shared conformance corpus in
 * {@code schemas/src/test/resources/conformance}, wired onto this module's test classpath as
 * {@code /conformance}.
 *
 * <p>Covers OpenAPI, Swagger, AsyncAPI, GraphQL, WSDL, and protobuf (a single-file {@code .proto}
 * case, extracted through the synthetic {@code source.proto} the {@code extract(String, ...)} seam
 * names, so gRPC read-path extraction is now on the same golden gate as every other protocol).
 *
 * <p>Enabling it is the migration safety gate for de-materialization.
 */
class OperationSchemaExtractorParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OperationSchemaExtractor extractor = ExtractorTestParsers.extractor();
    private final ProtocolExtractionService protocolExtractionService =
            new ProtocolExtractionService(new ObjectMapper(), new YAMLMapper(),
                    new WsdlVersionParser(SAXParserFactory.newDefaultInstance()));

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusCases")
    void extractorReproducesExpectedSchemas(Path expected) throws Exception {
        JsonNode node = JSON.readTree(expected.toFile());
        String path = node.get("path").asText();
        String method = node.get("method").asText();

        Path input = findInput(expected.getParent());
        String rawSource = Files.readString(input);
        OperationProtocol protocol = protocolFor(input, rawSource);

        OperationSchemaExtractor.ExtractedSchemas result = extractor.extract(rawSource, protocol, path, method);

        assertNodeEquals(node.get("specification"), result.specification(), "specification", expected);
        assertNodeEquals(node.get("requestSchema"), result.requestSchema(), "requestSchema", expected);
        assertNodeEquals(node.get("responseSchemas"), result.responseSchemas(), "responseSchemas", expected);
    }

    private OperationProtocol protocolFor(Path input, String rawSource) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                input.getFileName().toString(),
                null,
                rawSource.getBytes(StandardCharsets.UTF_8));
        return protocolExtractionService.getOperationProtocol(List.of(file));
    }

    private static List<Path> corpusCases() throws IOException {
        return expectedCases();
    }
}
