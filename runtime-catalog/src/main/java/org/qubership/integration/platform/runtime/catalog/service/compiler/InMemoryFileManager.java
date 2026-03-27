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

import org.apache.commons.io.FilenameUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
    private final Map<URI, InMemoryOutputFileObject> outputMap;

    public InMemoryFileManager(JavaFileManager fileManager) {
        super(fileManager);
        outputMap = new HashMap<>();
    }

    public Collection<InMemoryOutputFileObject> getOutputFiles() {
        return outputMap.values();
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location,
            String className,
            JavaFileObject.Kind kind,
            FileObject sibling
    ) {
        URI uri = buildOutputURI(className, kind, sibling);
        InMemoryOutputFileObject outputFile = outputMap.get(uri);
        if (nonNull(outputFile)) {
            throw new IllegalStateException("Output file already exists: " + uri);
        }
        outputFile = new InMemoryOutputFileObject(uri, kind);
        outputMap.put(uri, outputFile);
        return outputFile;
    }

    private URI buildOutputURI(String className, JavaFileObject.Kind kind, FileObject sibling) {
        return nonNull(className) || isNull(sibling)
                ? URIUtils.buildURI(InMemoryOutputFileObject.SCHEME, className, kind)
                : buildURIUsingSibling(kind, sibling);
    }

    private URI buildURIUsingSibling(JavaFileObject.Kind kind, FileObject sibling) {
        String path = FilenameUtils.removeExtension(sibling.toUri().getPath()) + kind.extension;
        try {
            return new URI(InMemoryOutputFileObject.SCHEME, null, path, null);
        } catch (URISyntaxException exception) {
            throw new RuntimeException(exception);
        }
    }
}
