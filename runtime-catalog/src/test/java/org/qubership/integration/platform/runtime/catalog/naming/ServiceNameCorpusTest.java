package org.qubership.integration.platform.runtime.catalog.naming;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.io.model.exportimport.ExportImportConstants;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This module measured against the shared naming corpus in {@code schemas/src/test/resources/naming}.
 *
 * <p>Every other test of the file names in this module is single sided: it compares this module against itself, so the
 * whole suite stays green while this module and the VS Code extension drift apart. Measured — renaming one postfix
 * constant reddens 21 cases here and none in the extension. What makes this test different is that the expected names
 * are computed from the corpus rule rather than spelled here, and the extension computes its own from the same rule.
 *
 * <p>The corpus is not an oracle of this module's behaviour, and it is not regenerable from it. A red case is a
 * question about which side broke the rule.
 */
class ServiceNameCorpusTest {

    private static final JsonNode CORPUS = NameCorpusSupport.corpus();

    private static final Set<String> PLAIN_KINDS =
            Set.copyOf(NameCorpusSupport.strings(CORPUS.path("alphabet").path("plainKinds")));

    private static final ServiceTypeFiles SERVICE_TYPE_FILES =
            new ServiceTypeFiles(new ApplicationJsonSchemaProperties());

    // --- invariant 1: the generators produce what the rule says --------------------------------------------------

    @Test
    @DisplayName("every generator produces the name the corpus rule computes")
    void generatorsFollowTheDeclaredRule() {
        assertTrue(ids().stream().anyMatch(ExportImportUtils::fitsCurrentFormatFileName),
                "the corpus alphabet holds no id the current format can name");

        forEachNameableTriple((id, appName, kind) ->
                assertEquals(NameCorpusSupport.currentFormatName(CORPUS, id, kind, appName), generate(id, appName, kind),
                        () -> "the generator for " + kind + " departs from the corpus rule for id '" + id + "'"));
    }

    // --- invariant 2: a produced name reads back as the same id and kind -----------------------------------------

    @Test
    @DisplayName("a current-format name reads back as the id it was built from")
    void currentFormatNamesRoundTrip() {
        forEachNameableTriple((id, appName, kind) -> {
            File file = new File(NameCorpusSupport.currentFormatName(CORPUS, id, kind, appName));

            // The two extractors are not interchangeable: the plain one has to tell the flat name apart first,
            // because a plain service has two formats and the other two kinds have one.
            String readId = PLAIN_KINDS.contains(kind)
                    ? ExportImportUtils.extractSystemIdFromFileName(file)
                    : ExportImportUtils.extractSystemIdFromCurrentFormatFileName(file);
            assertEquals(id, readId, () -> "the name of " + kind + " service '" + id + "' states another id");
        });
    }

    /**
     * A per-type name is a format this side reads and no longer writes, and it reads the same id back. Nothing else
     * here measures that, and it is what keeps every archive exported during #553 importable.
     */
    @Test
    @DisplayName("a per-type name still reads back as its id")
    void perTypeNamesStillReadBackTheirId() {
        for (String id : ids()) {
            if (!ExportImportUtils.fitsCurrentFormatFileName(id)) {
                continue;
            }
            for (String appName : appNames()) {
                for (String kind : PLAIN_KINDS) {
                    File file = new File(NameCorpusSupport.perTypeName(CORPUS, id, kind, appName));

                    assertEquals(id, ExportImportUtils.extractSystemIdFromFileName(file),
                            () -> file.getName() + " states another id");
                }
            }
        }
    }

    // --- invariant 3: names collide exactly where the rule says they do -------------------------------------------

    /**
     * The three plain kinds share one name on purpose, so a collision between them is the rule rather than a defect.
     * Everything else has to stay distinct: two ids, two app names, or a plain kind against a context or MCP one.
     */
    @Test
    @DisplayName("only the three plain kinds share a current-format name")
    void currentFormatNamesCollideOnlyAcrossThePlainKinds() {
        Map<String, String> byName = new LinkedHashMap<>();
        forEachNameableTriple((id, appName, kind) -> {
            String name = generate(id, appName, kind);
            String key = (PLAIN_KINDS.contains(kind) ? "PLAIN" : kind) + "|" + id + "|" + appName;
            String clash = byName.put(name, key);
            assertTrue(clash == null || clash.equals(key),
                    () -> name + " is produced by both " + clash + " and " + key);
        });
    }

    // --- invariant 3b: the $schema states what the name no longer does ---------------------------------------------

    /**
     * The type carrier, measured against the rule rather than against this module's own constants. Both layers are
     * exercised: the URI this installation is configured with, and the schema's own file name, which is what types a
     * document written by an installation configured differently.
     */
    @Test
    @DisplayName("each declared $schema reads back as the type the corpus records")
    void declaredSchemasReadBackAsTheirType() {
        JsonNode types = CORPUS.path("types");
        for (String kind : PLAIN_KINDS) {
            IntegrationSystemType expected = IntegrationSystemType.valueOf(kind);
            String defaultUri = types.path("defaultSchemaUris").path(kind).asText();
            String stem = types.path("schemaFileStems").path(kind).asText();

            assertEquals(defaultUri, SERVICE_TYPE_FILES.schemaUri(expected),
                    () -> "this side is configured with another URI for " + kind);
            assertEquals(Optional.of(expected), SERVICE_TYPE_FILES.typeFromSchemaUri(defaultUri),
                    () -> defaultUri + " does not read back as " + kind);
            assertEquals(Optional.of(expected),
                    SERVICE_TYPE_FILES.typeFromSchemaUri("https://elsewhere.example/" + stem + ".schema.yaml"),
                    () -> "the file-name layer does not read " + stem + " back as " + kind);
        }

        // Per-side outcomes: this side reads none of these as a type, and the corpus has to agree —
        // the extension resolves CONTEXT and MCP here, and that divergence is written down there.
        for (JsonNode entry : types.path("statingNoPlainType")) {
            String uri = entry.path("uri").asText();
            assertEquals("no-type", entry.path("java").asText(),
                    () -> uri + ": the corpus no longer records the java outcome this side enforces");
            assertEquals(Optional.empty(), SERVICE_TYPE_FILES.typeFromSchemaUri(uri),
                    () -> uri + " states a type, and the corpus records that this side reads none");
        }
    }

    // --- invariant 4, in this module's spelling: a refusal is an exception ----------------------------------------

    @Test
    @DisplayName("an id the current format cannot state is refused, for every kind")
    void anIdSpanningSegmentsIsRefused() {
        for (String id : ids()) {
            if (ExportImportUtils.fitsCurrentFormatFileName(id)) {
                continue;
            }
            for (String kind : kinds()) {
                assertThrows(ServiceExportException.class, () -> generate(id, "qip", kind),
                        () -> "id '" + id + "' produced a " + kind + " name instead of being refused");
            }
        }
        assertTrue(ids().stream().anyMatch(id -> !ExportImportUtils.fitsCurrentFormatFileName(id)),
                "the corpus alphabet holds no id the current format has to refuse");
    }

    // --- invariant 5: classification, per side, disagreements declared --------------------------------------------

    @Test
    @DisplayName("every classify case reads the way the corpus records it for this side")
    void classifyCasesMatchTheRecordedJavaOutcome(@TempDir Path directory) throws IOException {
        Set<String> caseNames = new LinkedHashSet<>();
        for (JsonNode entry : CORPUS.path("classify")) {
            String caseName = entry.path("name").asText();
            assertTrue(caseNames.add(caseName), () -> "duplicate classify case name: " + caseName);

            String fileName = entry.path("fileName").asText();
            assertTrue(entry.hasNonNull("appName"), () -> caseName + " states no appName, so it has two readings");
            assertTrue(entry.hasNonNull("directory"),
                    () -> caseName + " states no directory, and this side reads the postfix after it too");
            assertTrue(entry.hasNonNull("reason") || entry.hasNonNull("divergence"),
                    () -> caseName + " states neither a reason nor a divergence");

            String expected = entry.path("java").asText();
            assertEquals(expected, classify(directory.resolve(caseName), entry),
                    () -> caseName + " (" + entry.path("directory").asText() + "/" + fileName + ")");

            if (!expected.equals(entry.path("ts").asText())) {
                assertTrue(entry.hasNonNull("divergence"),
                        () -> caseName + " records a disagreement between the two sides with no written divergence");
            }
        }
        assertFalse(caseNames.isEmpty(), "the corpus holds no classify case");
    }

    // --- the flat names, and which of them anything discovers ------------------------------------------------------

    /**
     * The {@code discoverable} flags, measured through the real archive walk rather than read off a constant. The
     * context half is also covered by {@code ServiceTypeRoundTripTest}; the MCP flat name is asserted nowhere else,
     * and it is the one that makes {@code QIP_EXPORT_LEGACY_FORMAT} not a downgrade path for an MCP service.
     */
    @Test
    @DisplayName("only the plain flat name is discovered by an import")
    void flatNamesAreDiscoverableAsRecorded(@TempDir Path directory) throws IOException {
        JsonNode flat = CORPUS.path("rule").path("flat");
        String id = "svc-flat";
        Map<String, String> namesByKind = Map.of(
                "plain", ExportImportUtils.generateMainSystemFileExportName(id, "qip", true),
                "context", ExportImportUtils.generateMainContextServiceFileExportName(id, "qip", true),
                "mcp", ExportImportUtils.generateMCPServiceFileExportName(id, "qip", true));

        Path services = Files.createDirectories(
                directory.resolve(ExportImportConstants.ARCH_PARENT_DIR).resolve(id));
        for (String name : namesByKind.values()) {
            Files.writeString(services.resolve(name), "id: " + id + "\n");
        }

        // One scan per kind, with the postfixes that kind's import actually asks for. Asking for all of them at once
        // is a different question, and one no import poses.
        Map<String, List<String>> discovered = Map.of(
                "plain", scan(directory, ExportImportUtils.plainServicePostfixes()),
                "context", scan(directory, List.of(ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX)),
                "mcp", scan(directory, List.of(ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX)));

        namesByKind.forEach((kind, name) -> {
            boolean expected = flat.path(kind).path("discoverable").asBoolean();
            assertEquals(expected, discovered.get(kind).contains(name),
                    () -> "the corpus records " + kind + " flat name '" + name + "' as discoverable=" + expected);
        });
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static List<String> scan(Path root, List<String> postfixes) throws IOException {
        return ExportImportUtils.extractSystemsFromImportDirectory(root.toString(), postfixes).stream()
                .map(File::getName)
                .sorted()
                .toList();
    }

    /**
     * Whether any import discovers this file — answered by writing it into an archive tree and running the three real
     * scans over it, one per import, with the postfixes that import asks for.
     *
     * <p>Not a predicate assembled here from {@code statesPostfix} and friends. A hand-composed one drifts from the
     * real filter in three ways at once: it misses the {@code scansPlainServices} gate, it reaches for the name-only
     * {@code statesPostfix} overload rather than the one that also anchors on the directory, and it asks for all five
     * postfixes at once, which is a scan no import performs. The corpus would then record a rule that exists only in
     * this file.
     */
    private static String classify(Path root, JsonNode entry) throws IOException {
        Path folder = Files.createDirectories(
                root.resolve(ExportImportConstants.ARCH_PARENT_DIR).resolve(entry.path("directory").asText()));
        String fileName = entry.path("fileName").asText();
        Files.writeString(folder.resolve(fileName), "id: placeholder\n");

        List<List<String>> scansEachImportRuns = List.of(
                ExportImportUtils.plainServicePostfixes(),
                List.of(ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX),
                List.of(ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX));
        for (List<String> postfixes : scansEachImportRuns) {
            if (scan(root, postfixes).contains(fileName)) {
                return "service";
            }
        }
        return "not-a-service";
    }

    private static String generate(String id, String appName, String kind) {
        return switch (kind) {
            case "CONTEXT" -> ExportImportUtils.generateMainContextServiceFileExportName(id, appName, false);
            case "MCP" -> ExportImportUtils.generateMCPServiceFileExportName(id, appName, false);
            default -> ExportImportUtils.generateMainSystemFileExportName(id, appName, false);
        };
    }

    /** Every triple the current format can name, so a refusal case never reaches an assertion about a name. */
    private static void forEachNameableTriple(TripleAssertion assertion) {
        for (String id : ids()) {
            if (!ExportImportUtils.fitsCurrentFormatFileName(id)) {
                continue;
            }
            for (String appName : appNames()) {
                for (String kind : kinds()) {
                    assertion.accept(id, appName, kind);
                }
            }
        }
    }

    private static List<String> ids() {
        return NameCorpusSupport.strings(CORPUS.path("alphabet").path("serviceIds"));
    }

    private static List<String> appNames() {
        return NameCorpusSupport.strings(CORPUS.path("alphabet").path("appNames"));
    }

    private static List<String> kinds() {
        return NameCorpusSupport.strings(CORPUS.path("alphabet").path("kinds"));
    }

    @FunctionalInterface
    private interface TripleAssertion {
        void accept(String id, String appName, String kind);
    }
}
