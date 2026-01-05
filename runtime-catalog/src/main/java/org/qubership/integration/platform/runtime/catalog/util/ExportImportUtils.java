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
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants;
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
                : id + SPECIFICATION_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
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

        OperationProtocol protocol = source.getSystemModel().getSpecificationGroup().getSystem().getProtocol();
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

    public static String generateMainSystemFileExportName(String id, String appName, boolean isLegacyExport) {
        return isLegacyExport
                ? SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION
                : id + SERVICE_YAML_NAME_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    public static String generateMainContextServiceFileExportName(String id, String appName, boolean isLegacyExport) {
        return isLegacyExport
                ? CONTEXT_SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION
                : id + CONTEXT_SERVICE_YAML_NAME_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
    }

    public static String generateSourceExportDir(String id) {
        return SOURCE_YAML_NAME_PREFIX + id;
    }

    public static String generateSpecificationGroupFileExportName(String id, String appName, boolean isLegacyExport) {
        return isLegacyExport
                ? SPECIFICATION_GROUP_FILE_PREFIX + id + "." + YAML_EXTENSION
                : id + SPECIFICATION_GROUP_FILE_POSTFIX + appName + YAML_FILE_NAME_POSTFIX;
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
        try (ZipInputStream inputStream = new ZipInputStream(is)) {
            extractZip(importFolderName, inputStream);

            return extractSystemsFromImportDirectory(importFolderName, yamlPostfix);
        }
    }

    public static List<File> extractSystemsFromImportDirectory(String importFolderName, String yamlPostfix) throws IOException {
        Path start = Paths.get(importFolderName + File.separator + ARCH_PARENT_DIR);
        if (Files.exists(start)) {
            try (Stream<Path> sp = Files.walk(start)) {
                return sp.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(f -> (f.getName().startsWith(SERVICE_YAML_NAME_PREFIX) && f.getName().endsWith(YAML_EXTENSION))
                                     || f.getName().contains(yamlPostfix))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    public static String extractSystemIdFromFileName(File systemFile) {
        if (systemFile.getName().startsWith(SERVICE_YAML_NAME_PREFIX)) {
            return systemFile.getName().substring(SERVICE_YAML_NAME_PREFIX.length(), systemFile.getName().lastIndexOf("."));
        } else {
            return systemFile.getName().substring(0, systemFile.getName().indexOf("."));
        }
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
