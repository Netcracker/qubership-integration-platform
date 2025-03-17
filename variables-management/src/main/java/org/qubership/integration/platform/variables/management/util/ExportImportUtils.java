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

package org.qubership.integration.platform.variables.management.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.util.regex.Pattern;

@Slf4j
public class ExportImportUtils {

    private static final Pattern YAML_FILE_EXTENSION_REGEXP = Pattern.compile("yaml|yml", Pattern.CASE_INSENSITIVE);

    public static ResponseEntity<Object> bytesAsResponse(byte[] content, String fileName) {
        HttpHeaders header = new HttpHeaders();
        header.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName);
        header.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        ByteArrayResource resource = new ByteArrayResource(content);
        return ResponseEntity.ok()
                .headers(header)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    public static void deleteFile(String directoryString) {
        deleteFile(new File(directoryString));
    }

    private static void deleteFile(File directory) {
        FileUtils.deleteQuietly(directory);
    }

    public static boolean isYamlFile(String fileName) {
        String fileExtension = FilenameUtils.getExtension(fileName);
        return YAML_FILE_EXTENSION_REGEXP.matcher(fileExtension).matches();
    }
}
