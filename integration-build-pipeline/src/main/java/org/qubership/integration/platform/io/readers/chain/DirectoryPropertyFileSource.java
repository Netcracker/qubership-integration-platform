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

package org.qubership.integration.platform.io.readers.chain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.qubership.integration.platform.io.readers.chain.ImportConstants.RESOURCES_FOLDER_PREFIX;

/**
 * Reads element property files from a chain directory on disk.
 *
 * <p>Resolves a name against the chain directory, falling back to the {@code resources/} subfolder,
 * and rejects any path that escapes the base directory.
 */
public class DirectoryPropertyFileSource implements PropertyFileSource {

    private final File chainFilesDir;

    public DirectoryPropertyFileSource(File chainFilesDir) {
        this.chainFilesDir = chainFilesDir;
    }

    @Override
    public String getFileContent(String fileName) throws IOException {
        Path basePath = chainFilesDir.toPath();
        Path targetPath = basePath.resolve(fileName).normalize();

        if (!targetPath.startsWith(basePath)) {
            throw new IOException("Access to the file is outside the base directory");
        }

        if (!targetPath.toFile().isFile()) {
            if (!fileName.contains(RESOURCES_FOLDER_PREFIX)) {
                return getFileContent(RESOURCES_FOLDER_PREFIX + fileName);
            }
            throw new IOException("Directory " + chainFilesDir.getName() + " does not contain file: " + fileName);
        }

        return Files.readString(targetPath);
    }
}
