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

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.nonNull;

public class MultipartFileUtils {
    private static final String ZIP_EXTENSION = "zip";
    private static final String ZIP_CONTENT_TYPE = "application/x-zip-compressed";

    private static class ByteBufferMultipartFile implements MultipartFile {
        private final String fileName;
        private final byte[] data;

        public ByteBufferMultipartFile(String fileName, byte[] data) {
            this.fileName = fileName;
            this.data = data;
        }

        @Override
        public String getName() {
            return FilenameUtils.getName(fileName);
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return data.length == 0;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return data;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(data);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            try (OutputStream out = new FileOutputStream(dest)) {
                IOUtils.write(data, out);
            }
        }
    }

    public static Collection<MultipartFile> extractArchives(MultipartFile[] files) throws IOException {
        List<MultipartFile> result = new ArrayList<>();
        for (MultipartFile file : files) {
            if (isZipArchive(file)) {
                result.addAll(getZipArchiveContent(file));
            } else {
                result.add(file);
            }
        }
        return result;
    }

    private static boolean isZipArchive(MultipartFile file) {
        return ZIP_CONTENT_TYPE.equals(file.getContentType())
                || ZIP_EXTENSION.equals(FilenameUtils.getExtension(file.getOriginalFilename()));
    }

    private static Collection<MultipartFile> getZipArchiveContent(MultipartFile file) throws IOException {
        List<MultipartFile> result = new ArrayList<>();
        try (ZipInputStream in = new JarInputStream(file.getInputStream())) {
            ZipEntry entry = in.getNextEntry();
            while (nonNull(entry)) {
                if (!entry.isDirectory()) {
                    String name = entry.getName();
                    byte[] data = IOUtils.toByteArray(in);
                    result.add(new ByteBufferMultipartFile(name, data));
                }
                entry = in.getNextEntry();
            }
        }
        return result;
    }

    private MultipartFileUtils() {}
}
