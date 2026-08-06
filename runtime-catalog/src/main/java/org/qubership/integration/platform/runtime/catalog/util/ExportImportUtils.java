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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;


@Slf4j
public class ExportImportUtils {

    public static final String IMPORT_TMP_DIR_PATH = "/tmp/";

    private static final String RE_CREATE_UNDER_A_FLAT_ID =
            "Re-create the service under an id of one dot-free segment.";

    private static final String EXPORT_IN_THE_LEGACY_FORMAT =
            "Export with QIP_EXPORT_LEGACY_FORMAT=true, whose flat name states the id whole and carries no type.";

    // Every postfix a plain-service export writes: the three per-type ones and the older `.service.`. They are also
    // the only postfixes that can outrank the deprecated flat name, which is the plain service's own second format.
    private static final List<String> PLAIN_SERVICE_POSTFIXES = List.of(
            SERVICE_YAML_NAME_POSTFIX,
            EXTERNAL_SERVICE_YAML_NAME_POSTFIX,
            INTERNAL_SERVICE_YAML_NAME_POSTFIX,
            IMPLEMENTED_SERVICE_YAML_NAME_POSTFIX);

    public static String generateArchiveExportName() {
        DateFormat dateFormat = new SimpleDateFormat(DATE_TIME_FORMAT_PATTERN);
        return EXPORT_FILE_NAME_PREFIX + dateFormat.format(new Date()) + "." + ZIP_EXTENSION;
    }

    public static boolean isPropertiesFileGroove(Map<String, Object> properties) {
        boolean result = false;
        if (properties != null) {
            result = GROOVY_EXTENSION.equals(properties.get(EXPORT_FILE_EXTENSION_PROPERTY))
                     || isAfterScriptInServiceCall(properties)
                     || isBeforeScriptInServiceCall(properties);
        }
        return result;
    }

    public static boolean isPropertiesFileSql(Map<String, Object> properties) {
        boolean result = false;
        if (properties != null) {
            result = SQL_EXTENSION.equals(properties.get(EXPORT_FILE_EXTENSION_PROPERTY));
        }
        return result;
    }

    public static boolean isPropertiesFileJson(Map<String, Object> properties) {
        boolean result = false;
        if (properties != null) {
            result = JSON_EXTENSION.equals(properties.get(EXPORT_FILE_EXTENSION_PROPERTY));
        }
        return result;
    }

    public static String getFileContentByName(File chainFilesDir, String fileName) throws IOException {
        Path targetPath = chainFilesDir.toPath().resolve(fileName).normalize();
        File targetFile = targetPath.toFile();

        if (!targetPath.startsWith(chainFilesDir.toPath())) {
            throw new IOException("Access to the file is outside the base directory");
        }

        if (!targetFile.isFile()) {
            if (!fileName.contains(RESOURCES_FOLDER_PREFIX)) {
                return getFileContentByName(chainFilesDir, RESOURCES_FOLDER_PREFIX + fileName);
            }
            throw new RuntimeException("Directory " + chainFilesDir.getName() + " does not contain file: " + fileName);
        }

        return Files.readString(targetPath);
    }

    public static File extractDirectoriesFromZip(InputStream is, String importFolderName) throws IOException {
        ZipInputStream inputStream = new ZipInputStream(is);
        File importFolder = new File(IMPORT_TMP_DIR_PATH + importFolderName);
        Path path = Paths.get(IMPORT_TMP_DIR_PATH + importFolderName);
        for (ZipEntry entry; (entry = inputStream.getNextEntry()) != null; ) {
            Path resolvedPath = path.resolve(entry.getName());
            Path normalizedResolvedPath = resolvedPath.normalize();

            if (normalizedResolvedPath.startsWith(path)) {
                if (!entry.isDirectory()) {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(inputStream, resolvedPath);
                    Files.setLastModifiedTime(resolvedPath, FileTime.fromMillis(entry.getTime()));
                } else {
                    Files.createDirectories(resolvedPath);
                }
            }
        }
        inputStream.close();
        return importFolder;
    }

    public static void deleteFile(File directory) {
        FileUtils.deleteQuietly(directory);
    }

    public static void deleteFile(String directoryString) {
        deleteFile(new File(directoryString));
    }

    public static Boolean isAfterScriptInServiceCall(Map properties) {
        List<Map<String, Object>> afterList = (List<Map<String, Object>>) properties.get(AFTER);
        if (!CollectionUtils.isEmpty(afterList)) {
            for (Map<String, Object> after : afterList) {
                if (null != after && SCRIPT.equals(after.get(TYPE))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Boolean isBeforeScriptInServiceCall(Map properties) {
        Map innerProperties = (Map) properties.get(BEFORE);
        return null != innerProperties && SCRIPT.equals(innerProperties.get(TYPE));
    }

    public static ZipEntry generateSourceEntry(SpecificationSource specificationSource, String dirPrefix) {
        String zipEntryPrefix = generateSourceExportDir(specificationSource.getSystemModel().getId());
        if (!StringUtils.isEmpty(dirPrefix)) {
            zipEntryPrefix = dirPrefix + File.separator + zipEntryPrefix;
        }
        String filename = ExportImportUtils.getSpecificationFileName(specificationSource);
        return new ZipEntry(zipEntryPrefix + File.separator + filename);
    }

    public static String generateSpecificationFileExportName(String id, String appName, boolean isLegacyExport) {
        return isLegacyExport
                ? SPECIFICATION_FILE_PREFIX + id + "." + YAML_EXTENSION
                : id + API_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    public static void writeZip(ZipOutputStream zipOut, SystemModel systemModel) {
        writeZip(zipOut, systemModel, null);
    }

    public static void writeZip(ZipOutputStream zipOut, SystemModel systemModel, String dirPrefix) {
        for (SpecificationSource specificationSource : systemModel.getSpecificationSources()) {
            if (specificationSource.getSource() == null) {
                log.warn("Can't find source for specification {}", systemModel.getId());
                continue;
            }

            ZipEntry sourceEntry = generateSourceEntry(specificationSource, dirPrefix);
            try {
                zipOut.putNextEntry(sourceEntry);
                byte[] sources = specificationSource.getSource().getBytes();
                zipOut.write(sources, 0, sources.length);
                zipOut.closeEntry();
            } catch (IOException e) {
                throw new RuntimeException("Unknown exception while archive creation: " + e.getMessage());
            }
        }
    }

    public static String getSpecificationFileName(SpecificationSource source) {
        if (!StringUtils.isBlank(source.getName())) {
            return source.getName();
        }

        OperationProtocol protocol = source.getSystemModel().getApiGroup().getSystem().getProtocol();
        return source.getId() + "." + getFallbackExtensionByProtocol(protocol);
    }

    public static String getFallbackExtensionByProtocol(OperationProtocol protocol) {
        return switch (protocol) {
            case HTTP, AMQP, KAFKA -> "yml";
            case SOAP -> "xml";
            case GRAPHQL -> "graphql";
            default -> "";
        };
    }

    public static String getExtensionByProtocolAndContentType(OperationProtocol protocol, String contentType) {
        return switch (protocol) {
            case HTTP, AMQP, KAFKA -> contentType.contains("json") ? "json" : "yml";
            case SOAP -> "xml";
            case GRAPHQL -> "graphql";
            default -> "";
        };
    }

    /**
     * The service file name. The current format states the type in the name, so the type is required there; the legacy
     * flat name carries none and states it in {@code content.integrationSystemType} instead.
     */
    public static String generateMainSystemFileExportName(
            String id, String appName, boolean isLegacyExport, IntegrationSystemType type) {
        if (isLegacyExport) {
            requireLegacyFlatId(id);
            return SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION;
        }
        requireCurrentFormatId(id, "<id>.<type>-service.<app>.yaml",
                fitsLegacyFlatFileName(id) ? EXPORT_IN_THE_LEGACY_FORMAT : RE_CREATE_UNDER_A_FLAT_ID);
        return id + ServiceTypeFiles.postfix(type) + appName + YAML_FILE_NAME_POSTFIX;
    }

    public static String generateMainContextServiceFileExportName(String id, String appName, boolean isLegacyExport) {
        if (isLegacyExport) {
            return CONTEXT_SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION;
        }
        requireCurrentFormatId(id, "<id>" + CONTEXT_SERVICE_YAML_NAME_POSTFIX + "<app>.yaml", RE_CREATE_UNDER_A_FLAT_ID);
        return id + CONTEXT_SERVICE_YAML_NAME_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    public static String generateMCPServiceFileExportName(String id, String appName, boolean isLegacyExport) {
        if (isLegacyExport) {
            return MCP_SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION;
        }
        requireCurrentFormatId(id, "<id>" + MCP_SERVICE_YAML_NAME_POSTFIX + "<app>.yaml", RE_CREATE_UNDER_A_FLAT_ID);
        return id + MCP_SERVICE_YAML_NAME_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    /**
     * Refuses an id no current-format name can state, for every service kind. Import reads the id up to the first dot
     * and the postfix in the segment right after it, so an id spanning two segments produces a name discovery never
     * finds.
     *
     * <p>The remedy differs by kind because the legacy flat name is a fallback only where import discovers it, which is
     * the plain service alone: nothing scans for {@code context-service-<id>.yaml} or {@code mcp-service-<id>.yaml}.
     */
    private static void requireCurrentFormatId(String id, String nameShape, String remedy) {
        if (fitsCurrentFormatFileName(id)) {
            return;
        }
        throw new ServiceExportException(("Service id '%s' cannot be stated in a current-format file name (%s): the id"
                + " has to be one dot-free segment. The archive does not import back. %s")
                .formatted(id, nameShape, remedy));
    }

    /**
     * Refuses an id whose flat name would read as a current-format plain-service one. A name stating a plain-service
     * postfix right after the id is current-format by precedence, so such a flat name comes back as another id under a
     * type it never had.
     */
    private static void requireLegacyFlatId(String id) {
        if (fitsLegacyFlatFileName(id)) {
            return;
        }
        throw new ServiceExportException(("Service id '%s' cannot be stated in a legacy flat file name (%s<id>.yaml):"
                + " its second segment spells a plain-service postfix, so the name reads as a current-format one, under"
                + " another id and another type. %s").formatted(id, SERVICE_YAML_NAME_PREFIX, RE_CREATE_UNDER_A_FLAT_ID));
    }

    public static String generateSourceExportDir(String id) {
        return SOURCE_YAML_NAME_PREFIX + id;
    }

    public static String generateSpecificationGroupFileExportName(String id, String appName, boolean isLegacyExport) {
        return isLegacyExport
                ? SPECIFICATION_GROUP_FILE_PREFIX + id + "." + YAML_EXTENSION
                : id + API_GROUP_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    public static ResponseEntity<Object> convertFileToResponse(byte[] payload, String fileName) {
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
        header.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        ByteArrayResource resource = new ByteArrayResource(payload);
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    public static void writeSystemObject(ZipOutputStream zipOut, String filepath, String contentString) throws IOException {
        zipOut.putNextEntry(new ZipEntry(filepath));
        if (!StringUtils.isBlank(contentString)) {
            byte[] content = contentString.getBytes();
            zipOut.write(content, 0, content.length);
        }
        zipOut.closeEntry();
    }

    public static String getFullSpecificationFileName(SpecificationSource source) {
        return generateSourceExportDir(source.getSystemModel().getId())
               + File.separator + getSpecificationFileName(source);
    }

    public static List<File> extractSystemsFromZip(InputStream is, String importFolderName, String yamlPostfix) throws IOException {
        return extractSystemsFromZip(is, importFolderName, List.of(yamlPostfix));
    }

    public static List<File> extractSystemsFromZip(
            InputStream is, String importFolderName, Collection<String> yamlPostfixes) throws IOException {
        try (ZipInputStream inputStream = new ZipInputStream(is)) {
            extractZip(importFolderName, inputStream);

            return extractSystemsFromImportDirectory(importFolderName, yamlPostfixes);
        }
    }

    public static List<File> extractSystemsFromImportDirectory(String importFolderName, String yamlPostfix) throws IOException {
        return extractSystemsFromImportDirectory(importFolderName, List.of(yamlPostfix));
    }

    /**
     * Every service file of an unpacked archive: one carrying any of {@code yamlPostfixes} in its name, plus, for a
     * plain-service scan, the deprecated flat {@code service-<id>.yaml} name.
     *
     * <p>A plain service states its type in the name, so its discovery needs four postfixes at once. Take them in one
     * call rather than calling the single-postfix overload once per postfix: the flat-name check rides along with each
     * of them, so a legacy-named file would come back once per call and import once per copy.
     *
     * <p>The flat name is the plain service's own second format and belongs to that scan alone. Nothing discovers
     * {@code context-service-<id>.yaml} or {@code mcp-service-<id>.yaml}, so ORing the flat name into those two scans
     * only ever handed them a plain service to choke on.
     */
    public static List<File> extractSystemsFromImportDirectory(
            String importFolderName, Collection<String> yamlPostfixes) throws IOException {
        Path start = Paths.get(importFolderName + File.separator + ARCH_PARENT_DIR);
        if (Files.exists(start)) {
            boolean scansPlainServices = yamlPostfixes.stream().anyMatch(PLAIN_SERVICE_POSTFIXES::contains);
            try (Stream<Path> sp = Files.walk(start)) {
                return sp.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(f -> (scansPlainServices && isLegacyFlatServiceName(f.getName())
                                      && f.getName().endsWith(YAML_EXTENSION))
                                     || yamlPostfixes.stream().anyMatch(postfix -> statesPostfix(f.getName(), postfix)))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    /**
     * Whether the name states {@code postfix} where an export writes it: right after the id, which is the first
     * dot-free segment. Matching anywhere in the name lets an id or an app prefix that merely contains the text state
     * a postfix of its own, so an api group whose app prefix reads {@code .external-service.} would be scanned as a
     * service, and a service exported under one type would resolve as another.
     */
    public static boolean statesPostfix(String fileName, String postfix) {
        return fileName.startsWith(postfix, fileName.indexOf('.'));
    }

    /** Every postfix a plain-service export writes, for the import-side scan that reads all four at once. */
    public static List<String> plainServicePostfixes() {
        return PLAIN_SERVICE_POSTFIXES;
    }

    /**
     * Export naming and import parsing are exact inverses, and this is where the plain service's two name formats are
     * told apart. A legacy flat name states the id whole and no type; a current-format name states its type in exactly
     * one position, right after the id. The postfix decides; the prefix decides only where no plain-service postfix is
     * stated there.
     *
     * <p>The two shapes are ambiguous by construction: {@code service-orders.internal-service.qip.yaml} is both the
     * current-format name of INTERNAL service {@code service-orders} and the flat name of service
     * {@code orders.internal-service.qip}, so one reading has to win. The current format wins because an id wearing the
     * flat prefix is ordinary — autodiscovery takes the id from the Kubernetes service name
     * ({@code DiscoveryService.constructSystemId}), so a cloud service named {@code service-orders} has one. An id
     * whose second segment spells a plain-service postfix is hand-authored, and {@code requireLegacyFlatId} refuses to
     * write its flat name rather than let it be misread.
     *
     * <p>Only the four plain-service postfixes are weighed. Reading {@code .context-service.} and {@code .mcp-service.}
     * here too made {@code service-orders.context-service.qip.yaml} current-format, and the plain-service scan, which
     * carries neither postfix, then walked past a name every earlier version discovered. Those two kinds are told
     * apart by their own scan instead: it is the only one that asks for their postfix, and the flat name never joins
     * it.
     */
    public static boolean isLegacyFlatServiceName(String fileName) {
        return fileName.startsWith(SERVICE_YAML_NAME_PREFIX) && !statesAnyPlainServicePostfix(fileName);
    }

    private static boolean statesAnyPlainServicePostfix(String fileName) {
        return PLAIN_SERVICE_POSTFIXES.stream().anyMatch(postfix -> statesPostfix(fileName, postfix));
    }

    /**
     * Whether a current-format name built from {@code id} reads back as that same id and type. Import reads the id up
     * to the first dot, so the id has to be one dot-free segment; what it starts with does not matter.
     */
    public static boolean fitsCurrentFormatFileName(String id) {
        return id.indexOf('.') < 0;
    }

    /** Whether the legacy flat name built from {@code id} reads back as that same id and no type. */
    public static boolean fitsLegacyFlatFileName(String id) {
        return isLegacyFlatServiceName(SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION);
    }

    /** The id a plain-service file name states, in either format. */
    public static String extractSystemIdFromFileName(File systemFile) {
        String fileName = systemFile.getName();
        return isLegacyFlatServiceName(fileName)
                ? fileName.substring(SERVICE_YAML_NAME_PREFIX.length(), fileName.lastIndexOf("."))
                : fileName.substring(0, fileName.indexOf("."));
    }

    /**
     * The id a context or MCP file name states, which is always the first dot-free segment. Neither kind has a flat
     * name any import discovers, so a name that also reads as one belongs to the plain service and states this kind's
     * id in the current format regardless.
     */
    public static String extractSystemIdFromCurrentFormatFileName(File systemFile) {
        String fileName = systemFile.getName();
        return fileName.substring(0, fileName.indexOf("."));
    }

    private static void extractZip(String importFolderName, ZipInputStream inputStream) throws IOException {
        Path path = Paths.get(importFolderName);

        for (ZipEntry entry; (entry = inputStream.getNextEntry()) != null; ) {
            Path resolvedPath = path.resolve(entry.getName());
            Path normalizedResolvedPath = resolvedPath.normalize();
            Path entryPath = Paths.get(entry.getName());

            if (entryPath.startsWith(ExportImportConstants.ARCH_PARENT_DIR) && normalizedResolvedPath.startsWith(path)) {
                if (!entry.isDirectory()) {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(inputStream, resolvedPath);
                    Files.setLastModifiedTime(resolvedPath, FileTime.fromMillis(entry.getTime()));
                } else {
                    Files.createDirectories(resolvedPath);
                }
            }
        }
    }

    public static boolean isYamlFile(String fileName) {
        String fileExtension = FilenameUtils.getExtension(fileName);
        return YAML_FILE_EXTENSION_REGEXP.matcher(fileExtension).matches();
    }
}
