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

package org.qubership.integration.platform.runtime.catalog.service.compiler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class JarBuilder {
    public void writeJar(OutputStream outputStream, Map<String, byte[]> files, Manifest manifest) throws IOException {
        try (JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            Set<String> directories = new HashSet<>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String path = entry.getKey().startsWith("/") ? entry.getKey().substring(1) : entry.getKey();
                addDirectoryEntries(jarOutputStream, path, directories);
                byte[] data = entry.getValue();
                JarEntry jarEntry = new JarEntry(path);
                jarOutputStream.putNextEntry(jarEntry);
                jarOutputStream.write(data);
                jarOutputStream.closeEntry();
            }
        }
    }

    private void addDirectoryEntries(ZipOutputStream zipOutputStream, String pathToFile, Set<String> directories)
            throws IOException {
        Path path = Paths.get(pathToFile).getParent();
        for (int i = 1; i < path.getNameCount() + 1; ++i) {
            Path subPath = path.subpath(0, i);
            String directory = subPath + FileSystems.getDefault().getSeparator();
            if (!directories.contains(directory)) {
                zipOutputStream.putNextEntry(new ZipEntry(directory));
                zipOutputStream.closeEntry();
                directories.add(directory);
            }
        }
    }
}
