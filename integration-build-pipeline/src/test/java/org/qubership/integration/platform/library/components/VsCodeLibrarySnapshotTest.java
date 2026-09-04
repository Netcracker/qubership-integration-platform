package org.qubership.integration.platform.library.components;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.library.configuration.ElementDescriptorProperties;
import org.qubership.integration.platform.library.configuration.YamlMapperAutoConfiguration;
import org.qubership.integration.platform.library.model.LibraryElements;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The VS Code extension edits chains offline against a snapshot of this library, and applies its
 * defaults when it creates an element. A stale snapshot means the extension writes documents the
 * platform no longer recognises, so the two are compared here rather than by hand.
 *
 * <p>The comparison ignores key order and formatting; only the element set and each descriptor's
 * content matter. When it fails, the freshly built library is written to {@code target/} so it can
 * replace the committed snapshot.
 */
class VsCodeLibrarySnapshotTest {

    private static final Path SNAPSHOT = Path.of("..", "vscode-extension", "media", "library.json");
    private static final Path REGENERATED = Path.of("target", "library.json");

    // application.yml wires this from camel.constants.request-filter-header.name
    private static final String FILTER_HEADER_ALLOWLIST_NAME = "requestFilterHeaderAllowlist";

    // The catalog serves the library without nulls, and the snapshot is a copy of that response.
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @DisplayName("The library snapshot the VS Code extension ships matches the platform library")
    @Test
    void vsCodeSnapshotMatchesThePlatformLibrary() throws IOException {
        Map<String, JsonNode> platform = descriptorsOf(canonical(jsonMapper.valueToTree(loadLibrary())));
        Map<String, JsonNode> snapshot = descriptorsOf(canonical(jsonMapper.readTree(Files.readString(SNAPSHOT))));

        if (!platform.equals(snapshot)) {
            Files.createDirectories(REGENERATED.getParent());
            Files.writeString(REGENERATED, jsonMapper.writer(prettyPrinter())
                    .writeValueAsString(canonical(jsonMapper.valueToTree(loadLibrary()))) + "\n");
        }

        Set<String> missing = new TreeSet<>(platform.keySet());
        missing.removeAll(snapshot.keySet());
        Set<String> stale = new TreeSet<>(snapshot.keySet());
        stale.removeAll(platform.keySet());
        assertThat(missing)
                .as("elements the platform has and the snapshot lacks; copy %s over %s", REGENERATED, SNAPSHOT)
                .isEmpty();
        assertThat(stale)
                .as("elements the snapshot still ships and the platform has dropped; copy %s over %s",
                        REGENERATED, SNAPSHOT)
                .isEmpty();
        for (Map.Entry<String, JsonNode> element : new TreeMap<>(platform).entrySet()) {
            assertThat(snapshot.get(element.getKey()))
                    .as("descriptor of '%s'; copy %s over %s to refresh the snapshot",
                            element.getKey(), REGENERATED, SNAPSHOT)
                    .isEqualTo(element.getValue());
        }
    }

    private LibraryElements loadLibrary() {
        Properties properties = new Properties();
        properties.setProperty("filter-header-allowlist-name", FILTER_HEADER_ALLOWLIST_NAME);
        YamlMapperAutoConfiguration yamlConfiguration = new YamlMapperAutoConfiguration();
        LibraryElementsService registry = new LibraryElementsService(
                yamlConfiguration.defaultYamlMapper(yamlConfiguration.createCustomYamlFactory()),
                new PropertyPlaceholderHelper(
                        SystemPropertyUtils.PLACEHOLDER_PREFIX, SystemPropertyUtils.PLACEHOLDER_SUFFIX),
                new ElementDescriptorProperties(properties));

        LibraryResourceLoader loader = new LibraryResourceLoader(registry);
        ReflectionTestUtils.setField(loader, "folderResource", new ClassPathResource("elements/folders.yaml"));
        loader.load();

        return registry.getElementsHierarchy();
    }

    /**
     * Element and group order comes from a {@code HashMap}, so it is not stable between runs. Only
     * that ordering is normalised: inside a descriptor the order of properties, tabs and allowed
     * values is what the editor renders, so those are left exactly as the platform serialises them.
     */
    private JsonNode canonical(JsonNode node) {
        if (isDescriptor(node)) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode sorted = jsonMapper.createObjectNode();
            sortedFieldNames(node).forEach(field -> sorted.set(field, canonical(node.get(field))));
            return sorted;
        }
        if (node.isArray()) {
            List<JsonNode> children = new ArrayList<>();
            node.forEach(child -> children.add(canonical(child)));
            children.sort(Comparator.comparing(child -> child.path("name").asText("")));
            ArrayNode sorted = jsonMapper.createArrayNode();
            children.forEach(sorted::add);
            return sorted;
        }
        return node;
    }

    private static Set<String> sortedFieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Matches the two-space, {@code "key": value} style the committed snapshot is written in. */
    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        return printer;
    }

    private static boolean isDescriptor(JsonNode node) {
        return node.isObject() && node.has("name") && node.path("properties").isObject();
    }

    /** Flattens the group tree to element name -> descriptor, so grouping order cannot matter. */
    private Map<String, JsonNode> descriptorsOf(JsonNode library) {
        Map<String, JsonNode> descriptors = new TreeMap<>();
        collectDescriptors(library, descriptors);
        return descriptors;
    }

    private void collectDescriptors(JsonNode node, Map<String, JsonNode> descriptors) {
        if (node.isObject()) {
            if (isDescriptor(node)) {
                descriptors.put(node.get("name").asText(), node);
                return;
            }
            node.forEach(child -> collectDescriptors(child, descriptors));
        } else if (node.isArray()) {
            node.forEach(child -> collectDescriptors(child, descriptors));
        }
    }
}
