package org.qubership.integration.platform.runtime.catalog.service.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared access to the conformance corpus in {@code schemas/src/test/resources/conformance}, wired
 * onto this module's test classpath as {@code /conformance} by a {@code <testResource>} in the POM.
 *
 * <p>Every test that walks the corpus routes through here, so a change to the corpus layout or to
 * the numeric-comparison rule lands in one place instead of drifting across the callers.
 */
public final class CorpusTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CorpusTestSupport() {
    }

    /** Root of the corpus on the test classpath. Fails the calling test if the resource is not wired. */
    public static Path corpusRoot() {
        URL url = CorpusTestSupport.class.getResource("/conformance");
        assertNotNull(url, "Conformance corpus is not on the test classpath. "
                + "Check the <testResource> for schemas/src/test/resources/conformance in runtime-catalog/pom.xml.");
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every {@code *.expected.json} case in the corpus, in stable order. */
    public static List<Path> expectedCases() throws IOException {
        try (Stream<Path> walk = Files.walk(corpusRoot())) {
            return walk
                    .filter(path -> path.getFileName().toString().endsWith(".expected.json"))
                    .sorted()
                    .toList();
        }
    }

    /** The {@code *.expected.json} cases whose parent directory is in {@code caseDirs}. */
    public static List<Path> expectedCasesIn(Set<String> caseDirs) throws IOException {
        return expectedCases().stream()
                .filter(path -> caseDirs.contains(path.getParent().getFileName().toString()))
                .toList();
    }

    /** All {@code source.input.*} files in a case directory. Each case is expected to hold exactly one. */
    public static List<Path> listInputs(Path caseDir) throws IOException {
        try (Stream<Path> list = Files.list(caseDir)) {
            return list
                    .filter(path -> path.getFileName().toString().startsWith("source.input."))
                    .sorted()
                    .toList();
        }
    }

    /** The raw input specification for a case directory. */
    public static Path findInput(Path caseDir) throws IOException {
        return listInputs(caseDir).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No source.input.* in " + caseDir));
    }

    /** Reads the raw input specification for a case directory. */
    public static String readInput(Path caseDir) {
        try {
            return Files.readString(findInput(caseDir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compares an extracted schema value against its corpus expectation. */
    public static void assertNodeEquals(JsonNode expected, Object actualValue, String label) {
        assertNodeEquals(expected, actualValue, label, null);
    }

    /**
     * Compares an extracted schema value against its corpus expectation, treating a Java {@code null}
     * and a JSON {@code null} as the same absence.
     *
     * @param context appended to the failure message to identify the case; may be {@code null}
     */
    public static void assertNodeEquals(JsonNode expected, Object actualValue, String label, Object context) {
        JsonNode expectedNode = expected == null || expected.isNull() ? NullNode.getInstance() : expected;
        JsonNode actualNode = actualValue == null ? NullNode.getInstance() : JSON.valueToTree(actualValue);
        assertEquals(canonicalNumbers(expectedNode), canonicalNumbers(actualNode),
                () -> label + " mismatch" + (context == null ? "" : " for " + context));
    }

    /**
     * Normalizes numeric scale so numerically-equal numbers compare equal, and nothing else. The
     * corpus was seeded from jsonb, which reformats numbers ({@code maximum: 100} was stored as
     * {@code 100.0}), while a fresh parse yields {@code 1E+2}; both are the number 100.
     * {@code JsonNode.equals} uses scale-sensitive {@code BigDecimal.equals}, so every numeric node is
     * stripped to a canonical form before comparison. Keys are not reordered and no field is dropped,
     * so a genuine structural difference still fails the gate.
     */
    public static JsonNode canonicalNumbers(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isNumber()) {
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.fields().forEachRemaining(field -> result.set(field.getKey(), canonicalNumbers(field.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(child -> result.add(canonicalNumbers(child)));
            return result;
        }
        return node;
    }
}
