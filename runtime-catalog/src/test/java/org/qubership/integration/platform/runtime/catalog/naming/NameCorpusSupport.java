package org.qubership.integration.platform.runtime.catalog.naming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared access to the service file naming corpus in {@code schemas/src/test/resources/naming}, wired onto this
 * module's test classpath as {@code /naming} by a {@code <testResource>} in the POM.
 *
 * <p>The corpus states the naming rule for both implementations of it, so neither authors it. See the README beside
 * the corpus before changing an outcome recorded there.
 */
public final class NameCorpusSupport {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final String CORPUS_FILE = "service-file-names.yaml";

    private NameCorpusSupport() {
    }

    /** Root of the corpus on the test classpath. Fails the calling test if the resource is not wired. */
    public static Path corpusRoot() {
        URL url = NameCorpusSupport.class.getResource("/naming");
        assertNotNull(url, "Naming corpus is not on the test classpath. "
                + "Check the <testResource> for schemas/src/test/resources/naming in runtime-catalog/pom.xml.");
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The corpus document. */
    public static JsonNode corpus() {
        try {
            return YAML.readTree(corpusRoot().resolve(CORPUS_FILE).toFile());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The name the declared rule builds for a current-format file, so the test never spells a name itself. */
    public static String currentFormatName(JsonNode corpus, String id, String kind, String appName) {
        String postfix = corpus.path("rule").path("current").path("postfixes").path(kind).asText();
        return id + postfix + appName + ".yaml";
    }

    /** The same, for a per-type name — a format both implementations read and neither writes. */
    public static String perTypeName(JsonNode corpus, String id, String kind, String appName) {
        String postfix = corpus.path("rule").path("perType").path("postfixes").path(kind).asText();
        return id + postfix + appName + ".yaml";
    }

    public static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }
}
