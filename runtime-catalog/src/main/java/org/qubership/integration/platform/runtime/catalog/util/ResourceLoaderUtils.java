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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class ResourceLoaderUtils {

    private static final ResourcePatternResolver RESOURCE_RESOLVER =
            new PathMatchingResourcePatternResolver(ResourceLoaderUtils.class.getClassLoader());

    public static Map<String, Resource> loadFiles(String locationPattern) {
        try {
            return Arrays
                    .stream(RESOURCE_RESOLVER.getResources(locationPattern))
                    .map(descriptorFile -> extractDirPath(descriptorFile)
                            .map(dirPath -> Map.entry(dirPath, descriptorFile))
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (right, left) -> {
                        log.warn("Duplicated element is skipped: {}", left.getDescription());
                        return right;
                    }));
        } catch (IOException e) {
            log.error("Error loading file descriptors from classpath", e);
            throw new RuntimeException(e);
        }
    }

    private static Optional<String> extractDirPath(Resource descriptorFile) {
        try {
            String fileUri = descriptorFile.getURI().toString();
            return Optional.of(fileUri.substring(0, fileUri.lastIndexOf('/')));
        } catch (Exception e) {
            log.error("Error loading file description file", e);
            return Optional.empty();
        }
    }
}
