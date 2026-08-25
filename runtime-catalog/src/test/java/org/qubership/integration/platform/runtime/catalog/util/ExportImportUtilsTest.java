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

package org.qubership.integration.platform.runtime.catalog.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.AFTER;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.BEFORE;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SCRIPT;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.TYPE;

class ExportImportUtilsTest {

    // What discovery itself scans with, not a copy of it: a hand-written list could drop a read-only name and this
    // suite would still pass while an archive from the #553 window stopped importing.
    private static final List<String> SERVICE_POSTFIXES = ExportImportUtils.plainServicePostfixes();

    /**
     * The write side of the export-import filename contract. `ServiceDeserializer` matches the group file by name
     * before anything is parsed, so a typo in either constant produces an import with zero API groups and no error.
     */
    @Test
    void generatesTheApiGroupPostfixNameAndKeepsTheLegacyPrefixName() {
        assertEquals("group-1.api-group.qip.yaml",
                ExportImportUtils.generateSpecificationGroupFileExportName("group-1", "qip", false));
        assertEquals("specGroup-group-1.yaml",
                ExportImportUtils.generateSpecificationGroupFileExportName("group-1", "qip", true));
    }

    @Test
    void testExtractSystemIdFromFileNameWithLegacyServicePrefix() {
        File file = new File("service-myServiceId.yaml");
        assertEquals("myServiceId", ExportImportUtils.extractSystemIdFromFileName(file));
    }

    @Test
    void testExtractSystemIdFromFileNameWithContextServiceStyle() {
        File file = new File("ctx-id.context-service.app.yaml");
        assertEquals("ctx-id", ExportImportUtils.extractSystemIdFromFileName(file));
    }

    @Test
    void testExtractSystemIdFromFileNameWithSimpleName() {
        File file = new File("simple.yaml");
        assertEquals("simple", ExportImportUtils.extractSystemIdFromFileName(file));
    }

    @Test
    void testIsPropertiesFileGrooveWithNullProperties() {
        assertFalse(ExportImportUtils.isPropertiesFileGroove(null));
    }

    @Test
    void testIsPropertiesFileGrooveWithGroovyExtension() {
        assertTrue(ExportImportUtils.isPropertiesFileGroove(
                Map.of("exportFileExtension", "groovy")));
    }

    @Test
    void testIsPropertiesFileSqlWithNullProperties() {
        assertFalse(ExportImportUtils.isPropertiesFileSql(null));
    }

    @Test
    void testIsPropertiesFileSqlWithSqlExtension() {
        assertTrue(ExportImportUtils.isPropertiesFileSql(
                Map.of("exportFileExtension", "sql")));
    }

    @Test
    void testIsPropertiesFileJsonWithJsonExtension() {
        assertTrue(ExportImportUtils.isPropertiesFileJson(
                Map.of("exportFileExtension", "json")));
    }

    @Test
    void testIsAfterScriptInServiceCallWithScript() {
        Map<String, Object> props = Map.of(
                AFTER, List.of(Map.of(TYPE, SCRIPT))
        );
        assertTrue(ExportImportUtils.isAfterScriptInServiceCall(props));
    }

    @Test
    void testIsAfterScriptInServiceCallWithoutScript() {
        Map<String, Object> props = Map.of(
                AFTER, List.of(Map.of(TYPE, "other"))
        );
        assertFalse(ExportImportUtils.isAfterScriptInServiceCall(props));
    }

    @Test
    void testIsBeforeScriptInServiceCallWithScript() {
        Map<String, Object> props = Map.of(
                BEFORE, Map.of(TYPE, SCRIPT)
        );
        assertTrue(ExportImportUtils.isBeforeScriptInServiceCall(props));
    }

    @Test
    void testIsBeforeScriptInServiceCallWithNullInner() {
        assertFalse(ExportImportUtils.isBeforeScriptInServiceCall(Map.of()));
    }

    @Test
    void testGenerateArchiveExportNameReturnsNonEmptyWithZip() {
        String name = ExportImportUtils.generateArchiveExportName();
        assertNotNull(name);
        assertTrue(name.startsWith("export-"));
        assertTrue(name.endsWith(".zip"));
    }

    @Test
    void testDeleteFileWithStringDoesNotThrow() {
        ExportImportUtils.deleteFile("/nonexistent/path/12345");
    }

    @Test
    void testDeleteFileWithFileDoesNotThrow() {
        ExportImportUtils.deleteFile(new File("/nonexistent/path/12345"));
    }

    @Test
    void testGetFallbackExtensionByProtocolForHttp() {
        assertEquals("yml", ExportImportUtils.getFallbackExtensionByProtocol(OperationProtocol.HTTP));
    }

    @Test
    void testGetFallbackExtensionByProtocolForSoap() {
        assertEquals("xml", ExportImportUtils.getFallbackExtensionByProtocol(OperationProtocol.SOAP));
    }

    @Test
    void testGetFallbackExtensionByProtocolForGraphql() {
        assertEquals("graphql", ExportImportUtils.getFallbackExtensionByProtocol(OperationProtocol.GRAPHQL));
    }

    @Test
    void testGetExtensionByProtocolAndContentTypeForHttpJson() {
        assertEquals("json",
                ExportImportUtils.getExtensionByProtocolAndContentType(OperationProtocol.HTTP, "application/json"));
    }

    @Test
    void testGetExtensionByProtocolAndContentTypeForHttpYaml() {
        assertEquals("yml",
                ExportImportUtils.getExtensionByProtocolAndContentType(OperationProtocol.HTTP, "text/yaml"));
    }

    @Test
    void testGenerateMainContextServiceFileExportNameLegacy() {
        String name = ExportImportUtils.generateMainContextServiceFileExportName("id1", "app", true);
        assertEquals("context-service-id1.yaml", name);
    }

    @Test
    void testGenerateMainContextServiceFileExportNameNewFormat() {
        String name = ExportImportUtils.generateMainContextServiceFileExportName("id1", "app", false);
        assertEquals("id1.context-service.app.yaml", name);
    }

    @Test
    void testIsYamlFileWithYamlExtension() {
        assertTrue(ExportImportUtils.isYamlFile("file.yaml"));
    }

    @Test
    void testIsYamlFileWithYmlExtension() {
        assertTrue(ExportImportUtils.isYamlFile("file.yml"));
    }

    @Test
    void testIsYamlFileWithOtherExtension() {
        assertFalse(ExportImportUtils.isYamlFile("file.json"));
    }

    @Test
    void testExtractSystemsFromImportDirectoryEmptyWhenNoServicesDir(@TempDir Path tempDir) throws IOException {
        List<File> result = ExportImportUtils.extractSystemsFromImportDirectory(
                tempDir.toAbsolutePath().toString(), ".context-service.");
        assertEquals(Collections.emptyList(), result);
    }

    /**
     * Import discovery: the three per-type postfixes, the older {@code .service.}, and the deprecated flat prefix, in
     * one walk. Everything else stays out, which is what keeps context and MCP services on their own import path.
     */
    @Test
    void testExtractSystemsFromImportDirectoryFindsEveryPlainServiceAndNothingElse(@TempDir Path tempDir) throws IOException {
        writeServiceFile(tempDir, "svc-a", "svc-a.external-service.qip.yaml");
        writeServiceFile(tempDir, "svc-b", "svc-b.internal-service.qip.yaml");
        writeServiceFile(tempDir, "svc-c", "svc-c.implemented-service.qip.yaml");
        writeServiceFile(tempDir, "svc-d", "svc-d.service.qip.yaml");
        writeServiceFile(tempDir, "svc-e", "service-svc-e.yaml");
        writeServiceFile(tempDir, "svc-a", "grp-a.api-group.qip.yaml");
        writeServiceFile(tempDir, "ctx-a", "ctx-a.context-service.qip.yaml");
        writeServiceFile(tempDir, "mcp-a", "mcp-a.mcp-service.qip.yaml");

        List<File> found = ExportImportUtils.extractSystemsFromImportDirectory(
                tempDir.toAbsolutePath().toString(), SERVICE_POSTFIXES);

        assertEquals(
                List.of("service-svc-e.yaml",
                        "svc-a.external-service.qip.yaml",
                        "svc-b.internal-service.qip.yaml",
                        "svc-c.implemented-service.qip.yaml",
                        "svc-d.service.qip.yaml"),
                found.stream().map(File::getName).sorted().toList());
    }

    /**
     * The compatibility claim the per-type file names rest on, asserted: an older QIP scanned for {@code .service.}
     * and the flat prefix only, so it never discovers a per-type service. Such a service is silently absent from its
     * import result rather than reported as an error. Change a postfix so that it does match and this fails.
     */
    @Test
    void testExtractSystemsFromImportDirectoryWithOneLegacyPostfixFindsNoPerTypeName(@TempDir Path tempDir) throws IOException {
        writeServiceFile(tempDir, "svc-a", "svc-a.external-service.qip.yaml");
        writeServiceFile(tempDir, "svc-b", "svc-b.internal-service.qip.yaml");
        writeServiceFile(tempDir, "svc-c", "svc-c.implemented-service.qip.yaml");

        List<File> found = ExportImportUtils.extractSystemsFromImportDirectory(
                tempDir.toAbsolutePath().toString(), SERVICE_YAML_NAME_POSTFIX);

        assertEquals(Collections.emptyList(), found);
    }

    /**
     * The postfix is read where an export writes it, right after the id. Scanning the whole name instead pulls in
     * every other file of the archive as soon as the configured app prefix carries a service postfix, and the group
     * file then fails deserialization as a service.
     */
    @Test
    void testExtractSystemsFromImportDirectorySkipsAPostfixInTheAppPrefix(@TempDir Path tempDir) throws IOException {
        writeServiceFile(tempDir, "svc-a", "svc-a.external-service.app.internal-service.qip.yaml");
        writeServiceFile(tempDir, "svc-a", "grp-a.api-group.app.internal-service.qip.yaml");

        List<File> found = ExportImportUtils.extractSystemsFromImportDirectory(
                tempDir.toAbsolutePath().toString(), SERVICE_POSTFIXES);

        assertEquals(List.of("svc-a.external-service.app.internal-service.qip.yaml"),
                found.stream().map(File::getName).toList());
    }

    /**
     * Why discovery takes the postfixes together rather than one at a time: the flat prefix is ORed in on every call,
     * so four single-postfix calls return a legacy-named file four times and the archive imports it four times over.
     */
    @Test
    void testExtractSystemsFromImportDirectoryFindsALegacyNamedFileOnce(@TempDir Path tempDir) throws IOException {
        writeServiceFile(tempDir, "svc-e", "service-svc-e.yaml");
        String directory = tempDir.toAbsolutePath().toString();

        List<File> together = ExportImportUtils.extractSystemsFromImportDirectory(directory, SERVICE_POSTFIXES);

        assertEquals(1, together.size(), "one file, one walk, one result: " + together);
        int oneAtATime = 0;
        for (String postfix : SERVICE_POSTFIXES) {
            oneAtATime += ExportImportUtils.extractSystemsFromImportDirectory(directory, postfix).size();
        }
        assertEquals(SERVICE_POSTFIXES.size(), oneAtATime,
                "the single-postfix overload returns the legacy file for every postfix, so the results must never"
                        + " be summed");
    }

    /**
     * The id reads back off the name whatever the app prefix spells. A prefix carrying a service postfix of its own is
     * the shape that used to shift the name's one readable position; the id is still read at the first dot.
     */
    @Test
    void testExportedNameReadsBackAsTheIdItWasBuiltFrom() {
        String fileName = ExportImportUtils.generateMainSystemFileExportName("svc-a", "app.internal-service.qip", false);

        assertEquals("svc-a.service.app.internal-service.qip.yaml", fileName);
        assertEquals("svc-a", ExportImportUtils.extractSystemIdFromFileName(new File(fileName)), fileName);
    }

    /**
     * The format that states an id the current one cannot: the flat name holds the id whole and leaves the type to
     * the document, so export and import stay exact inverses for every id it can state.
     */
    @Test
    void testLegacyNameReadsBackADottedId() {
        String serviceId = "svc-a.v2";

        String fileName = ExportImportUtils.generateMainSystemFileExportName(serviceId, "qip", true);

        assertEquals("service-" + serviceId + ".yaml", fileName);
        assertEquals(serviceId, ExportImportUtils.extractSystemIdFromFileName(new File(fileName)));
    }

    /**
     * The one id shape neither name states. {@code service-svc-a.external-service.v2.yaml} is also the current-format
     * name of service {@code service-svc-a}, and the current format wins that tie, so the flat name would come back as
     * another id under a type this service never had. The export refuses it rather than write it.
     */
    @Test
    void testLegacyNameRefusesAnIdWhoseSecondSegmentSpellsAPostfix() {
        String serviceId = "svc-a.external-service.v2";

        ServiceExportException refusal = assertThrows(ServiceExportException.class, () ->
                ExportImportUtils.generateMainSystemFileExportName(serviceId, "qip", true));

        assertTrue(refusal.getMessage().contains(serviceId),
                "the message names the id to fix: " + refusal.getMessage());
    }

    /**
     * Autodiscovery mints a service id from the Kubernetes service name, so an id wearing the flat prefix is ordinary
     * rather than hand-authored. Both names state it: the current one because the postfix, not the prefix, tells the
     * two formats apart, and the flat one because it reads the id back whole.
     */
    @Test
    void testBothNamesStateAnIdWearingTheLegacyFlatPrefix() {
        String serviceId = "service-orders";

        String currentName = ExportImportUtils.generateMainSystemFileExportName(serviceId, "qip", false);
        String flatName = ExportImportUtils.generateMainSystemFileExportName(serviceId, "qip", true);

        assertEquals(serviceId + SERVICE_YAML_NAME_POSTFIX + "qip.yaml", currentName);
        assertEquals(serviceId, ExportImportUtils.extractSystemIdFromFileName(new File(currentName)));
        assertEquals("service-" + serviceId + ".yaml", flatName);
        assertEquals(serviceId, ExportImportUtils.extractSystemIdFromFileName(new File(flatName)));
    }

    /**
     * The same round trip for the two kinds whose name carries no type: the name their export writes is the name their
     * own import scan discovers, and the id reads back whole. The app prefix here carries a postfix of its own, the
     * shape the anchored scan exists to tell apart.
     */
    @Test
    void testContextAndMcpNamesAreDiscoveredByTheirOwnScan(@TempDir Path tempDir) throws IOException {
        String contextName = ExportImportUtils.generateMainContextServiceFileExportName(
                "ctx-1", "app.mcp-service.qip", false);
        String mcpName = ExportImportUtils.generateMCPServiceFileExportName(
                "mcp-1", "app.context-service.qip", false);
        writeServiceFile(tempDir, "ctx-1", contextName);
        writeServiceFile(tempDir, "mcp-1", mcpName);
        String directory = tempDir.toAbsolutePath().toString();

        assertEquals(List.of(contextName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                directory, CONTEXT_SERVICE_YAML_NAME_POSTFIX)));
        assertEquals(List.of(mcpName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                directory, MCP_SERVICE_YAML_NAME_POSTFIX)));
        assertEquals("ctx-1", ExportImportUtils.extractSystemIdFromFileName(new File(contextName)));
        assertEquals("mcp-1", ExportImportUtils.extractSystemIdFromFileName(new File(mcpName)));
    }

    /**
     * An archive an older Runtime Catalog wrote for an id spanning several segments. Reading the postfix at the first
     * dot alone walked past all three of these, so services those archives hold went missing from the import with no
     * error row. The directory each file sits in is named after the service, which is where the postfix is read from
     * once the first dot answers nothing.
     */
    @Test
    void testADottedIdIsDiscoveredByItsOwnScanAndNoOther(@TempDir Path tempDir) throws IOException {
        String plainName = "a.b" + SERVICE_YAML_NAME_POSTFIX + "qip.yaml";
        String contextName = "ctx.part" + CONTEXT_SERVICE_YAML_NAME_POSTFIX + "qip.yaml";
        String mcpName = "mcp.part" + MCP_SERVICE_YAML_NAME_POSTFIX + "qip.yaml";
        writeServiceFile(tempDir, "a.b", plainName);
        writeServiceFile(tempDir, "ctx.part", contextName);
        writeServiceFile(tempDir, "mcp.part", mcpName);
        String directory = tempDir.toAbsolutePath().toString();

        assertEquals(List.of(plainName),
                fileNames(ExportImportUtils.extractSystemsFromImportDirectory(directory, SERVICE_POSTFIXES)));
        assertEquals(List.of(contextName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                directory, CONTEXT_SERVICE_YAML_NAME_POSTFIX)));
        assertEquals(List.of(mcpName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                directory, MCP_SERVICE_YAML_NAME_POSTFIX)));
    }

    /**
     * Extraction has to read back the id discovery anchored on. Cutting at the first dot alone read
     * {@code a.b} as {@code a}: the collision grouping then piled it onto service {@code a}, an ignore
     * instruction for {@code a} wrongly claimed it, and every status row named an id nobody wrote.
     */
    @Test
    void testADottedIdReadsBackFromTheDirectoryItWasDiscoveredIn() {
        File dottedPlain = new File("services/a.b", "a.b" + SERVICE_YAML_NAME_POSTFIX + "qip.yaml");
        File dottedPerType = new File("services/a.b", "a.b.external-service.qip.yaml");
        File plainSharingTheFirstSegment = new File("services/a", "a" + SERVICE_YAML_NAME_POSTFIX + "qip.yaml");
        // A file outside its service directory has only the first-dot rule, same as before.
        File strayDotted = new File("services/other", "a.b" + SERVICE_YAML_NAME_POSTFIX + "qip.yaml");

        assertEquals("a.b", ExportImportUtils.extractSystemIdFromFileName(dottedPlain));
        assertEquals("a.b", ExportImportUtils.extractSystemIdFromFileName(dottedPerType));
        assertEquals("a", ExportImportUtils.extractSystemIdFromFileName(plainSharingTheFirstSegment));
        assertEquals("a", ExportImportUtils.extractSystemIdFromFileName(strayDotted));
    }

    /**
     * The directory reads a dotted id back, but it does not reopen the name to every file of the archive: an api group
     * whose app prefix spells a service postfix is named after the group, not after the service, so the service
     * directory leaves it out.
     */
    @Test
    void testTheDirectoryDoesNotClaimAnotherEntitysFile(@TempDir Path tempDir) throws IOException {
        String serviceName = "a.b" + SERVICE_YAML_NAME_POSTFIX + "qip.yaml";
        writeServiceFile(tempDir, "a.b", serviceName);
        writeServiceFile(tempDir, "a.b", "grp-a.api-group.app.internal-service.qip.yaml");
        writeServiceFile(tempDir, "a.b", "a.b.context-service.qip.yaml");

        assertEquals(List.of(serviceName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                tempDir.toAbsolutePath().toString(), SERVICE_POSTFIXES)));
    }

    /**
     * The flat name is the plain service's own legacy form: nothing scans for {@code context-service-<id>.yaml} or
     * {@code mcp-service-<id>.yaml}, so a flat-named file joins a plain-service scan and no other. ORing it into every
     * scan put a plain service in the context and the MCP import as well.
     */
    @Test
    void testTheLegacyFlatNameJoinsThePlainScanOnly(@TempDir Path tempDir) throws IOException {
        writeServiceFile(tempDir, "svc-e", "service-svc-e.yaml");
        String directory = tempDir.toAbsolutePath().toString();

        assertEquals(List.of("service-svc-e.yaml"), fileNames(
                ExportImportUtils.extractSystemsFromImportDirectory(directory, SERVICE_POSTFIXES)));
        assertEquals(Collections.emptyList(), ExportImportUtils.extractSystemsFromImportDirectory(
                directory, CONTEXT_SERVICE_YAML_NAME_POSTFIX));
        assertEquals(Collections.emptyList(), ExportImportUtils.extractSystemsFromImportDirectory(
                directory, MCP_SERVICE_YAML_NAME_POSTFIX));
    }

    /**
     * Only a plain-service postfix outranks the flat name, because only a plain service has both names. An id whose
     * second segment spells the context or the MCP postfix therefore reads back whole on the plain scan, the way every
     * archive written before the per-type names states it. Weighing the other two kinds' postfixes here as well left
     * such a file on no plain-service scan at all, and its service silently absent from the import.
     */
    @ParameterizedTest(name = "an id spelling {0}")
    @ValueSource(strings = {CONTEXT_SERVICE_YAML_NAME_POSTFIX, MCP_SERVICE_YAML_NAME_POSTFIX})
    void testTheLegacyFlatNameStatesAnIdSpellingAnotherKindsPostfix(String postfix, @TempDir Path tempDir)
            throws IOException {
        String serviceId = "orders" + postfix + "qip";
        String fileName = ExportImportUtils.generateMainSystemFileExportName(serviceId, "qip", true);
        writeServiceFile(tempDir, serviceId, fileName);
        String directory = tempDir.toAbsolutePath().toString();

        assertEquals("service-" + serviceId + ".yaml", fileName);
        assertEquals(serviceId, ExportImportUtils.extractSystemIdFromFileName(new File(fileName)));
        assertEquals(List.of(fileName), fileNames(
                ExportImportUtils.extractSystemsFromImportDirectory(directory, SERVICE_POSTFIXES)));
        assertEquals(Collections.emptyList(), ExportImportUtils.extractSystemsFromImportDirectory(
                directory, theOtherTypelessPostfix(postfix)));
    }

    /**
     * The one name the two formats spell alike: it is the context name of {@code service-ctx} and the flat name of
     * {@code ctx.context-service.qip}, and no rule reads both out of one string. Each scan claims it in its own
     * format — the context scan under the id its own export wrote, the plain scan under the flat id — so neither an
     * older archive nor a current one loses a file. The MCP scan, which is neither, no longer sees it at all.
     */
    @Test
    void testAContextNameWearingTheLegacyFlatPrefixIsClaimedByBothFormats(@TempDir Path tempDir) throws IOException {
        String fileName = ExportImportUtils.generateMainContextServiceFileExportName("service-ctx", "qip", false);
        writeServiceFile(tempDir, "service-ctx", fileName);
        String directory = tempDir.toAbsolutePath().toString();

        assertEquals("service-ctx" + CONTEXT_SERVICE_YAML_NAME_POSTFIX + "qip.yaml", fileName);
        assertEquals(List.of(fileName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                directory, CONTEXT_SERVICE_YAML_NAME_POSTFIX)));
        assertEquals("service-ctx",
                ExportImportUtils.extractSystemIdFromCurrentFormatFileName(new File(fileName)));
        assertEquals(List.of(fileName), fileNames(ExportImportUtils.extractSystemsFromImportDirectory(
                directory, SERVICE_POSTFIXES)));
        assertEquals("ctx" + CONTEXT_SERVICE_YAML_NAME_POSTFIX + "qip",
                ExportImportUtils.extractSystemIdFromFileName(new File(fileName)));
        assertEquals(Collections.emptyList(), ExportImportUtils.extractSystemsFromImportDirectory(
                directory, MCP_SERVICE_YAML_NAME_POSTFIX));
    }

    /**
     * Export naming and import parsing stay exact inverses for all five service kinds, so the two that carry no type
     * refuse the same ids the three typed ones refuse. Their legacy flat name is no fallback: nothing scans for it.
     */
    @Test
    void testContextAndMcpRefuseAnIdTheCurrentFormatCannotState() {
        String serviceId = "ctx.part";

        ServiceExportException contextRefusal = assertThrows(ServiceExportException.class, () ->
                ExportImportUtils.generateMainContextServiceFileExportName(serviceId, "qip", false));
        ServiceExportException mcpRefusal = assertThrows(ServiceExportException.class, () ->
                ExportImportUtils.generateMCPServiceFileExportName(serviceId, "qip", false));

        assertTrue(contextRefusal.getMessage().contains(serviceId),
                "the message names the id to fix: " + contextRefusal.getMessage());
        assertTrue(mcpRefusal.getMessage().contains(serviceId),
                "the message names the id to fix: " + mcpRefusal.getMessage());
        assertFalse(contextRefusal.getMessage().contains("QIP_EXPORT_LEGACY_FORMAT"),
                "the legacy context name is discovered by no import: " + contextRefusal.getMessage());
    }

    @Test
    void testGetFileContentByNameThrowsWhenFileOutsideBase(@TempDir Path tempDir) throws IOException {
        File baseDir = tempDir.resolve("base").toFile();
        Files.createDirectories(baseDir.toPath());
        assertThrows(IOException.class, () ->
                ExportImportUtils.getFileContentByName(baseDir, "../../../etc/passwd"));
    }

    @Test
    void testGetFileContentByNameReturnsContentForExistingFile(@TempDir Path tempDir) throws IOException {
        File baseDir = tempDir.toFile();
        Path filePath = baseDir.toPath().resolve("test.txt");
        Files.writeString(filePath, "hello");
        assertEquals("hello", ExportImportUtils.getFileContentByName(baseDir, "test.txt"));
    }

    private static List<String> fileNames(List<File> files) {
        return files.stream().map(File::getName).sorted().toList();
    }

    /** The postfix of the other kind that states no type in its name, for a negative case that is not the plain scan. */
    private static String theOtherTypelessPostfix(String postfix) {
        return CONTEXT_SERVICE_YAML_NAME_POSTFIX.equals(postfix)
                ? MCP_SERVICE_YAML_NAME_POSTFIX
                : CONTEXT_SERVICE_YAML_NAME_POSTFIX;
    }

    private static void writeServiceFile(Path root, String serviceDirectory, String fileName) throws IOException {
        Path path = root.resolve("services").resolve(serviceDirectory).resolve(fileName);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "id: ignored\n");
    }
}
