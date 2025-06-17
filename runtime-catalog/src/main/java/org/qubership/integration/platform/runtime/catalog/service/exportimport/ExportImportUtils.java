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

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.*;


@Slf4j
public class ExportImportUtils {

    private static final String IMPORT_TMP_DIR_PATH = "/tmp/";

    public static String generateArchiveExportName() {
        DateFormat dateFormat = new SimpleDateFormat(DATE_TIME_FORMAT_PATTERN);
        return EXPORT_FILE_NAME_PREFIX + dateFormat.format(new Date()) + "." + ZIP_EXTENSION;
    }

    public static ArrayList<String> getPropertiesToExportInSeparateFile(ChainElement element) {
        Map<String, Object> properties = element.getProperties();
        ArrayList<String> propsNames = new ArrayList<>();

        if (properties != null) {
            if (properties.get(PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY) != null) {
                List<String> convertedPropsList =
                        Stream.of(((String) properties.get(PROPS_EXPORT_IN_SEPARATE_FILE_PROPERTY))
                                        .replace(" ", "")
                                        .split(",", -1))
                                .collect(Collectors.toList());
                if (!CollectionUtils.isEmpty(convertedPropsList)) {
                    propsNames.addAll(convertedPropsList);
                }
            }
        }
        return propsNames;
    }

    public static String generatePropertiesFileName(ChainElement element) {
        String prefix;
        Map<String, Object> properties = element.getProperties();
        ArrayList<String> propsToExportInSeparateFile = getPropertiesToExportInSeparateFile(element);

        if (element.getType().startsWith(MAPPER)) {
            prefix = propsToExportInSeparateFile.size() == 1
                    ? propsToExportInSeparateFile.get(0) : "mapper";
        } else {
            prefix = propsToExportInSeparateFile.size() == 1
                    ? propsToExportInSeparateFile.get(0) : "properties";
        }
        String extension = properties != null && properties.containsKey(EXPORT_FILE_EXTENSION_PROPERTY)
                ? properties.get(EXPORT_FILE_EXTENSION_PROPERTY).toString() : DEFAULT_EXTENSION;
        return prefix + "-" + element.getId() + "." + extension;
    }

    public static String generateAfterScriptFileName(String id, Map<String, Object> afterProp) {
        return SCRIPT + DASH + getIdOrCode(afterProp) + DASH + id + "." + GROOVY_EXTENSION;
    }

    public static Object getIdOrCode(Map<String, Object> mapProp) {
        return mapProp.get(ID) == null ? mapProp.get(CODE) : mapProp.get(ID);
    }

    public static String generateBeforeScriptFileName(String id) {
        return SCRIPT + DASH + BEFORE + DASH + id + "." + GROOVY_EXTENSION;
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
        File[] foundFiles = chainFilesDir.listFiles((dir, name) -> name.equals(fileName));
        if (ArrayUtils.isEmpty(foundFiles)) {
            throw new RuntimeException("Directory " + chainFilesDir.getName() + " does not contain file: " + fileName);
        }

        return Files.readString(foundFiles[0].toPath());
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

    public static String getPureDomainName(String domainName) {
        String result = "";
        Pattern pattern = Pattern.compile("cloud-integration-platform-engine-(.*?)-v1");
        Matcher matcher = pattern.matcher(domainName);
        if (matcher.find()) {
            result = matcher.group(1);
        }
        return result;
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

    public static boolean isScriptInServiceCall(ChainElement element) {
        return SERVICE_CALL.equals(element.getType())
                && (isAfterScriptInServiceCall(element.getProperties())
                        || isBeforeScriptInServiceCall(element.getProperties()));
    }

    public static boolean isMapperInServiceCall(ChainElement element) {
        if (SERVICE_CALL.equals(element.getType())) {
            List<Map<String, Object>> afterList = (List<Map<String, Object>>) element.getProperties().get(AFTER);
            if (!CollectionUtils.isEmpty(afterList)) {
                for (Map<String, Object> after : afterList) {
                    if (null != after && null != after.get(TYPE) && ((String) after.get(TYPE)).contains(MAPPER)) {
                        return true;
                    }
                }
            }
            Map<String, Object> beforeProperties = (Map<String, Object>) element.getProperties().get(BEFORE);
            if (!CollectionUtils.isEmpty(beforeProperties)) {
                if (null != beforeProperties.get(TYPE)) {
                    return ((String) beforeProperties.get(TYPE)).contains(MAPPER);
                }
            }
        }
        return false;
    }

    public static String generateAfterMapperFileName(String id, Map<String, Object> afterProp) {
        return MAPPING_DESCRIPTION + DASH + getIdOrCode(afterProp) + DASH + id + "." + JSON_EXTENSION;
    }

    public static String generateBeforeMapperFileName(String id, Map<String, Object> afterProp) {
        return MAPPING_DESCRIPTION + DASH + BEFORE + DASH + id + "." + JSON_EXTENSION;
    }

    public static ZipEntry generateSourceEntry(SpecificationSource specificationSource, String dirPrefix) {
        String zipEntryPrefix = generateSourceExportDir(specificationSource.getSystemModel().getId());
        if (!StringUtils.isEmpty(dirPrefix)) {
            zipEntryPrefix = dirPrefix + File.separator + zipEntryPrefix;
        }
        String filename = ExportImportUtils.getSpecificationFileName(specificationSource);
        return new ZipEntry(zipEntryPrefix + File.separator + filename);
    }

    public static String generateSpecificationFileExportName(String id) {
        return SPECIFICATION_FILE_PREFIX + id + "." + YAML_EXTENSION;
    }

    public static JsonPointer toJsonPointer(String... values) {
        String expression = Stream.of(values).collect(Collectors.joining("/", "/", ""));
        return JsonPointer.compile(expression);
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

    public static String getSpecificationFileName(JsonNode specificationSourceNode, OperationProtocol protocol) {
        String filename = getNodeAsText(specificationSourceNode.get(AbstractSystemEntity.Fields.name));
        if (!StringUtils.isBlank(filename)) {
            return filename;
        }

        return specificationSourceNode.get(AbstractSystemEntity.Fields.id).asText() + "." + getFallbackExtensionByProtocol(protocol);
    }

    public static String getSpecificationFileName(SpecificationSource source) {
        if (!StringUtils.isBlank(source.getName())) {
            return source.getName();
        }

        OperationProtocol protocol = source.getSystemModel().getSpecificationGroup().getSystem().getProtocol();
        return source.getId() + "." + getFallbackExtensionByProtocol(protocol);
    }

    public static String generateDeprecatedSourceExportDir(JsonNode specificationGroup, JsonNode specification) {
        return SOURCE_YAML_NAME_PREFIX + specificationGroup.get(AbstractSystemEntity.Fields.name).asText() + "-"
                + specification.get(AbstractSystemEntity.Fields.name).asText();
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

    public static String generateMainSystemFileExportName(String id) {
        return SERVICE_YAML_NAME_PREFIX + id + "." + YAML_EXTENSION;
    }

    public static String generateSourceExportDir(String id) {
        return SOURCE_YAML_NAME_PREFIX + id;
    }

    public static String generateSpecificationGroupFileExportName(String id) {
        return SPECIFICATION_GROUP_FILE_PREFIX + id + "." + YAML_EXTENSION;
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

    public static String getNodeAsText(JsonNode node) {
        if (node != null) {
            return node.asText();
        }
        return null;
    }

    public static List<File> extractSystemsFromZip(InputStream is, String importFolderName) throws IOException {
        try (ZipInputStream inputStream = new ZipInputStream(is)) {
            extractZip(importFolderName, inputStream, ARCH_PARENT_DIR);

            return extractSystemsFromImportDirectory(importFolderName);
        }
    }

    public static List<File> extractSystemsFromImportDirectory(String importFolderName) throws IOException {
        Path start = Paths.get(importFolderName + File.separator + ARCH_PARENT_DIR);
        if (Files.exists(start)) {
            try (Stream<Path> sp = Files.walk(start)) {
                return sp.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(f -> f.getName().startsWith(SERVICE_YAML_NAME_PREFIX) && f.getName().endsWith(YAML_EXTENSION))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    public static String extractSystemIdFromFileName(File systemFile) {
        return systemFile.getName().substring(SERVICE_YAML_NAME_PREFIX.length(), systemFile.getName().lastIndexOf("."));
    }

    private static void extractZip(String importFolderName, ZipInputStream inputStream, String archParentDir) throws IOException {
        Path path = Paths.get(importFolderName);

        for (ZipEntry entry; (entry = inputStream.getNextEntry()) != null; ) {
            Path resolvedPath = path.resolve(entry.getName());
            Path normalizedResolvedPath = resolvedPath.normalize();
            Path entryPath = Paths.get(entry.getName());

            if (entryPath.startsWith(archParentDir) && normalizedResolvedPath.startsWith(path)) {
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

    public static String getFileContent(File file) throws IOException {
        return Files.readString(file.toPath());
    }

    public static boolean isYamlFile(String fileName) {
        String fileExtension = FilenameUtils.getExtension(fileName);
        return YAML_FILE_EXTENSION_REGEXP.matcher(fileExtension).matches();
    }
}
