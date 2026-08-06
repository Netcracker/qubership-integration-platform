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
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.AFTER;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.BEFORE;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SCRIPT;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.TYPE;

class ExportImportUtilsTest {

    private static final List<String> SERVICE_POSTFIXES = Stream.concat(
            Stream.of(SERVICE_YAML_NAME_POSTFIX), ServiceTypeFiles.postfixes().stream()).toList();

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

    private static void writeServiceFile(Path root, String serviceDirectory, String fileName) throws IOException {
        Path path = root.resolve("services").resolve(serviceDirectory).resolve(fileName);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "id: ignored\n");
    }
}
