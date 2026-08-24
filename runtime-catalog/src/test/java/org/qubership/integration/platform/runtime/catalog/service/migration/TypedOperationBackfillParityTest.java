package org.qubership.integration.platform.runtime.catalog.service.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.parsers.impl.WsdlSpecificationParser;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ProtocolExtractionService;
import org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Parity gate for the whole backend half of the migration: {@link TypedOperationBackfill} must derive
 * {@code method} and {@code path} that equal the corpus values byte for byte. Those two columns end up in chain
 * element properties as {@code integrationOperationPath}, which the engine resolves at runtime, so any drift breaks
 * deployed chains. The oracle is the shared conformance corpus in
 * {@code schemas/src/test/resources/conformance} (38 operations across 10 models), wired onto this module's test
 * classpath as {@code /conformance} through {@link CorpusTestSupport}.
 *
 * <p>Every case is column-derivable except WSDL, whose {@code typed} needs a reparse of the main source; the derived
 * values there are constants ({@code POST} / {@code ""}), so the reparse only has to produce a non-null
 * {@link org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation}. The corpus feeds the
 * raw source for every case; column-derived protocols ignore it.
 *
 * <p>For openapi and asyncapi the derivation is identity-shaped — {@code method} and {@code path} pass through the
 * typed round trip unchanged — so the discriminating weight sits on the graphql, grpc, and wsdl cases, where the
 * derived values are reconstructed rather than copied.
 */
class TypedOperationBackfillParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WsdlVersionParser wsdlVersionParser = new WsdlVersionParser(SAXParserFactory.newDefaultInstance());
    private final ProtocolExtractionService protocolExtractionService =
            new ProtocolExtractionService(new ObjectMapper(), new YAMLMapper(), wsdlVersionParser);
    // grpc-payments carries no java_package option, so the (javaPackage ?? package) fallback branch is exercised by
    // Task 1's TypedOperationTest, not by this gate.
    private final TypedOperationBackfill backfill = new TypedOperationBackfill(
            protocolExtractionService,
            new WsdlSpecificationParser(new WsdlVersionParser(javax.xml.parsers.SAXParserFactory.newInstance())));

    @Test
    void corpusExposesAllThirtyEightOperations() throws IOException {
        assertEquals(38, CorpusTestSupport.expectedCases().size(),
                "The parity gate is calibrated for 38 corpus operations; the corpus layout changed.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusCases")
    void backfilledTypedDerivesMethodAndPathByteForByte(Path expected) throws Exception {
        JsonNode node = JSON.readTree(expected.toFile());
        String path = node.get("path").asText();
        String method = node.get("method").asText();

        Path caseDir = expected.getParent();
        String rawSource = CorpusTestSupport.readInput(caseDir);
        OperationProtocol protocol = protocolFor(CorpusTestSupport.findInput(caseDir), rawSource);

        Operation operation = new Operation();
        operation.setPath(path);
        operation.setMethod(method);
        operation.setSpecification(node.get("specification"));
        operation.setName(node.get("operationId").asText()); // WSDL reparse keys typed operations by name

        TypedOperation typed = backfill.backfillTyped(operation, operation.getSpecification(), protocol, rawSource);

        assertNotNull(typed, () -> "backfill produced no typed for " + expected);
        assertEquals(method, typed.deriveMethod(), () -> "method must derive byte for byte for " + expected);
        assertEquals(path, typed.derivePath(), () -> "path must derive byte for byte for " + expected);
    }

    /**
     * The fixtures carry a serialized {@code typed} block that the extension's conformance runner consumes; the
     * backfill above recomputes {@code typed} from columns and never reads it, so without this the fixture blocks
     * (including the openapi 3.2 group the TS runner skips) are validated by nothing on the Java side. Deserialize
     * each block and assert it derives the golden method/path, so a wrong block fails here too.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusCases")
    void fixtureTypedBlockDerivesMethodAndPathByteForByte(Path expected) throws Exception {
        JsonNode node = JSON.readTree(expected.toFile());
        String path = node.get("path").asText();
        String method = node.get("method").asText();
        JsonNode typedNode = node.get("typed");

        assertNotNull(typedNode, () -> "fixture carries no typed block for " + expected);
        TypedOperation typed = JSON.treeToValue(typedNode, TypedOperation.class);
        assertEquals(method, typed.deriveMethod(),
                () -> "fixture typed block must derive method byte for byte for " + expected);
        assertEquals(path, typed.derivePath(),
                () -> "fixture typed block must derive path byte for byte for " + expected);
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
        return CorpusTestSupport.expectedCases();
    }
}
