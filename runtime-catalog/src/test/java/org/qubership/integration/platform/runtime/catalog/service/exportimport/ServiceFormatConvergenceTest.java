package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportableObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ContextServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.MCPSystemDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export, import what was exported, export again — and get the same archive.
 *
 * <p>Every other round-trip test in this module stops after the import and asserts on the entities. That answers "the
 * import read it", not "a re-export writes it back the same way", and the two differ wherever a field is written on
 * one side and dropped on the other: the entity looks right and the second archive is missing something. The fixed
 * point is the only shape that catches it, and it catches it as a diff.
 *
 * <p><b>Pre-committed decision.</b> {@code documentsOf} compares whole {@code ObjectNode}s, so one extra or missing
 * field fails. If a divergence shows up here, it is a finding to be explained — not a reason to loosen the comparison
 * to the fields that happen to agree.
 */
class ServiceFormatConvergenceTest {

    /**
     * The current format, over all five kinds at once. The export is the real archive writer, so the comparison
     * covers the entry paths as well as the documents.
     */
    @Test
    @DisplayName("the current format is a fixed point: export, import, export again")
    void currentFormatIsAFixedPoint(@TempDir Path first, @TempDir Path second) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(false), first);

        GoldenServiceCorpus.unzipInto(reExport(first, false), second);

        GoldenServiceCorpus.assertSameTree(first, second,
                "the second export writes different file names than the first");
    }

    /**
     * The downgrade, for the three plain types. The <b>service</b> documents are a fixed point from the first export:
     * the type field V105 restores, the flat name, and the migration list all come back identical.
     *
     * <p>The <b>api</b> document is not, and the difference is measured rather than hidden. The legacy format writes
     * each operation's raw {@code specification}; the current format does not, so a service whose operations were
     * never persisted with one exports without it. The import then fills it from the source
     * ({@code ServiceDeserializer.fillMissingOperationSpecifications}, fill-only-when-null), and the second export
     * writes it. That is one-time: the third export equals the second, which is what
     * {@link #theLegacyApiDocumentConvergesAfterOneRoundTrip} pins.
     */
    @Test
    @DisplayName("the legacy service documents are a fixed point")
    void legacyServiceDocumentsAreAFixedPoint(@TempDir Path first, @TempDir Path second) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(true), first);

        List<IntegrationSystem> imported = importPlainServices(first);
        assertEquals(3, imported.size(), "the legacy archive did not yield the three plain services");
        GoldenServiceCorpus.unzipInto(
                GoldenServiceCorpus.archive(GoldenServiceCorpus.exportServices(imported, true), true), second);

        // Scoped to the plain services: the context and MCP files of the first archive have no second-archive
        // counterpart, which is the last test's subject.
        List<String> plainNames = GoldenServiceCorpus.relativeFileNames(second);
        assertEquals(GoldenServiceCorpus.relativeFileNames(first).stream().filter(plainNames::contains).toList(),
                plainNames, "the second legacy export writes different file names than the first");

        Map<String, ObjectNode> before = GoldenServiceCorpus.documentsOf(first);
        Map<String, ObjectNode> after = GoldenServiceCorpus.documentsOf(second);
        List<String> serviceDocuments = plainNames.stream()
                .filter(name -> name.contains("/" + ExportImportConstants.SERVICE_YAML_NAME_PREFIX))
                .toList();
        assertEquals(3, serviceDocuments.size(), () -> "expected three service documents, got " + serviceDocuments);
        serviceDocuments.forEach(name ->
                assertEquals(before.get(name), after.get(name), name + " changed on re-export"));
    }

    /**
     * The api document reaches its fixed point after one round trip, so the difference above is a one-time fill and
     * not an archive that keeps growing. Written as its own case because it is the part of the legacy format that is
     * <b>not</b> stable from the first export, and a reader should see that stated rather than inferred from a
     * narrowed comparison.
     */
    @Test
    @DisplayName("the legacy api document converges after one round trip")
    void theLegacyApiDocumentConvergesAfterOneRoundTrip(
            @TempDir Path first, @TempDir Path second, @TempDir Path third) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(true), first);

        GoldenServiceCorpus.unzipInto(
                GoldenServiceCorpus.archive(GoldenServiceCorpus.exportServices(importPlainServices(first), true), true),
                second);

        GoldenServiceCorpus.unzipInto(
                GoldenServiceCorpus.archive(GoldenServiceCorpus.exportServices(importPlainServices(second), true), true),
                third);

        GoldenServiceCorpus.assertSameTree(second, third, "the third legacy export still moves");
    }

    /**
     * What the downgrade costs. The context half is already asserted by
     * {@code ServiceTypeRoundTripTest.aLegacyArchiveDowngradesPlainServicesOnly}; the MCP half is asserted here and
     * nowhere else, and it is the reason {@code QIP_EXPORT_LEGACY_FORMAT} is not a downgrade path for an MCP service.
     */
    @Test
    @DisplayName("the MCP service is written into the legacy archive and discovered by nothing")
    void theLegacyArchiveLosesTheMcpService(@TempDir Path directory) throws IOException {
        GoldenServiceCorpus.unzipInto(GoldenServiceCorpus.archive(true), directory);

        String mcpFileName = ExportImportUtils.generateMCPServiceFileExportName(
                GoldenServiceCorpus.MCP_SERVICE_ID, GoldenServiceCorpus.APP_NAME, true);
        assertTrue(GoldenServiceCorpus.relativeFileNames(directory).stream().anyMatch(n -> n.endsWith(mcpFileName)),
                "the legacy export did not write the MCP file at all, so this test measures nothing");

        // The MCP import scans for its own postfix. The flat name states none, so the file is there and unfound.
        List<File> discovered = ExportImportUtils.extractSystemsFromImportDirectory(
                directory.toString(), List.of(ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX));

        assertFalse(discovered.stream().anyMatch(file -> file.getName().equals(mcpFileName)),
                "the flat MCP name was discovered, which no version of the import does");
        assertEquals(List.of(), discovered.stream().map(File::getName).filter(n -> n.startsWith("mcp-service-")).toList(),
                "an mcp-service- file reached the import");
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** Reads every service document of an unpacked archive back in and exports the lot again. */
    private static byte[] reExport(Path root, boolean legacy) throws IOException {
        List<ExportableObject> exported = new ArrayList<>(
                GoldenServiceCorpus.exportServices(importPlainServices(root), legacy));
        try {
            for (ContextSystem context : importContextServices(root)) {
                exported.add(GoldenServiceCorpus.contextServiceSerializer(legacy).serialize(context));
            }
            for (MCPSystem mcp : importMcpServices(root)) {
                exported.add(GoldenServiceCorpus.mcpSystemSerializer(legacy).serialize(mcp));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return GoldenServiceCorpus.archive(exported, legacy);
    }

    private static List<IntegrationSystem> importPlainServices(Path root) throws IOException {
        ServiceDeserializer deserializer = GoldenServiceCorpus.deserializer();
        return importFiles(root, ExportImportUtils.plainServicePostfixes(), deserializer::deserializeSystem);
    }

    private static List<ContextSystem> importContextServices(Path root) throws IOException {
        ContextServiceDeserializer deserializer = GoldenServiceCorpus.contextServiceDeserializer();
        return importFiles(root, List.of(ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX),
                deserializer::deserializeSystem);
    }

    private static List<MCPSystem> importMcpServices(Path root) throws IOException {
        MCPSystemDeserializer deserializer = GoldenServiceCorpus.mcpSystemDeserializer();
        return importFiles(root, List.of(ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX), deserializer::deserialize);
    }

    /**
     * Every file the real archive walk finds for {@code postfixes}, read back in name order. The order matters: an
     * archive that only reproduces under one ordering would otherwise fail unevenly between the three kinds.
     */
    private static <T> List<T> importFiles(Path root, List<String> postfixes, Function<File, T> read)
            throws IOException {
        return ExportImportUtils.extractSystemsFromImportDirectory(root.toString(), postfixes).stream()
                .sorted(Comparator.comparing(File::getName))
                .map(read)
                .toList();
    }
}
