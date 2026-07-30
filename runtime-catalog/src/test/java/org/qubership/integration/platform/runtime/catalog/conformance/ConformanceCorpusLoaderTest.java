package org.qubership.integration.platform.runtime.catalog.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.expectedCases;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.listInputs;

/**
 * Structural gate over the shared conformance corpus in
 * {@code schemas/src/test/resources/conformance} (wired onto this module's test
 * classpath as {@code /conformance} via a {@code <testResource>} in the POM).
 *
 * <p>Scope is structure only: every case is present and well formed, the raw input
 * spec parses, and its sibling {@code *.expected.json} parses and carries the identity
 * keys. Whether the extractor reproduces those schemas is asserted separately, by
 * {@code OperationSchemaExtractorParityTest}. Keeping the two apart means a corpus that
 * is merely malformed fails here, with a clearer message than a parity mismatch.
 */
class ConformanceCorpusLoaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private static final List<String> IDENTITY_KEYS =
            List.of("modelId", "operationId", "path", "method", "sourceHash");
    private static final List<String> SCHEMA_KEYS =
            List.of("specification", "requestSchema", "responseSchemas");

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectedFiles")
    void expectedCaseIsWellFormedAndHasParseableInput(Path expected) throws Exception {
        JsonNode node = JSON.readTree(expected.toFile());

        for (String key : IDENTITY_KEYS) {
            assertTrue(node.hasNonNull(key), () -> expected + " is missing identity key: " + key);
        }
        // Schema keys must be present, but may be JSON null: WSDL and GraphQL cases
        // carry no schemas and that passthrough (null) is exactly what we lock here.
        for (String key : SCHEMA_KEYS) {
            assertTrue(node.has(key), () -> expected + " is missing schema key: " + key);
        }

        Path input = findInput(expected.getParent());
        parseInput(input);
    }

    @Test
    void corpusCoversEveryLocalProtocolFamily() throws IOException {
        Set<String> dirs = caseDirectories();
        List<String> requiredFamilies = List.of(
                "openapi32", "openapi31", "openapi30", "swagger20",
                "asyncapi30", "asyncapi26", "graphql", "wsdl");
        for (String family : requiredFamilies) {
            assertTrue(dirs.stream().anyMatch(dir -> dir.startsWith(family)),
                    () -> "Corpus is missing the '" + family + "' case family. Present: " + dirs);
        }
    }

    @Test
    void corpusIsNonTrivial() throws IOException {
        int caseCount = expectedFiles().size();
        assertTrue(caseCount >= 30, () -> "Expected at least 30 corpus cases, found " + caseCount);
    }

    private static List<Path> expectedFiles() throws IOException {
        return expectedCases();
    }

    /** Each case directory must hold exactly one input; the loader is what locks that shape. */
    private static Path findInput(Path caseDir) throws IOException {
        List<Path> inputs = listInputs(caseDir);
        assertEquals(1, inputs.size(),
                () -> "Expected exactly one source.input.* in " + caseDir + " but found " + inputs);
        return inputs.getFirst();
    }

    private static Set<String> caseDirectories() throws IOException {
        try (Stream<Path> list = Files.list(corpusRoot())) {
            return list
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static void parseInput(Path input) throws Exception {
        String name = input.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1);
        String content = Files.readString(input);
        assertFalse(content.isBlank(), () -> "Input source is empty: " + input);

        if ("json".equals(extension)) {
            assertNotNull(JSON.readTree(input.toFile()), () -> "Unparseable JSON input: " + input);
        } else if ("yaml".equals(extension) || "yml".equals(extension)) {
            assertNotNull(YAML.readTree(input.toFile()), () -> "Unparseable YAML input: " + input);
        } else if ("wsdl".equals(extension)) {
            parseXml(input);
        } else if ("graphql".equals(extension)) {
            assertTrue(content.contains("type ") || content.contains("{"),
                    () -> "Input does not look like GraphQL SDL: " + input);
        } else if ("proto".equals(extension)) {
            assertTrue(content.contains("service ") || content.contains("message ") || content.contains("syntax"),
                    () -> "Input does not look like a protobuf .proto: " + input);
        } else {
            fail("Unexpected corpus input extension for " + input);
        }
    }

    private static void parseXml(Path input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document document = factory.newDocumentBuilder().parse(input.toFile());
        assertNotNull(document.getDocumentElement(), () -> "XML input has no root element: " + input);
    }
}
