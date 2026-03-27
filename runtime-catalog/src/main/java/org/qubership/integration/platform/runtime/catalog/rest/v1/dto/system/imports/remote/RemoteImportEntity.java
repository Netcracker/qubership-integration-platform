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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.imports.remote;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.List;

@Getter
@Setter
@SuperBuilder
public class RemoteImportEntity {

    private String baseUri;
    private String fileUri;
    private File sourceFile;
    private List<File> exportedFiles;
    private String exportFolder;

    public String getFileName() {
        String result = FilenameUtils.getName(this.fileUri);
        if (result.isBlank()) {
            throw new RuntimeException("No filename");
        }
        return result;
    }

    public String getRelativeDirUri() {
        return formatUriDir(StringUtils.stripEnd(this.fileUri, getFileName()));
    }

    public String getFullUri() {
        return formatUriDir(this.baseUri) + StringUtils.stripStart(this.fileUri, "/");
    }

    public String getFileVersion() {
        String baseName = FilenameUtils.getBaseName(getFileName());
        int versionIndex = baseName.lastIndexOf('-') + 1;
        if (versionIndex > 0) {
            return baseName.substring(versionIndex);
        } else {
            return "";
        }
    }

    public String getFileUriWoVersion() {
        String baseName = FilenameUtils.getBaseName(getFileName());
        if (!getFileVersion().isBlank()) {
            return getRelativeDirUri() + baseName.substring(0, baseName.lastIndexOf(getFileVersion()) - 1)
                    + FilenameUtils.EXTENSION_SEPARATOR + FilenameUtils.getExtension(getFileName());
        } else {
            return getRelativeDirUri() + getFileName();
        }
    }

    private String formatUriDir(String uri) {
        return StringUtils.stripEnd(uri, "/") + "/";
    }

}
