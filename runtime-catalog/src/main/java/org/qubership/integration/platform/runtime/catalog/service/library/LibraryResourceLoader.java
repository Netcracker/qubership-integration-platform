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

package org.qubership.integration.platform.runtime.catalog.service.library;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.util.ResourceLoaderUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class LibraryResourceLoader {

    @Value("classpath:elements/folders.yaml")
    private Resource folderResource;

    private final LibraryElementsService registry;

    @Autowired
    public LibraryResourceLoader(LibraryElementsService registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void load() {
        try {
            this.registry.loadFoldersDescriptor(folderResource.getInputStream());
        } catch (IOException e) {
            log.error("Error loading folder descriptor file {}", folderResource.getFilename(), e);
        }

        Map<String, Resource> elementPatches = ResourceLoaderUtils.loadFiles("classpath*:element-patches/*.{yml|yaml}");

        for (Resource elementPatchFile : elementPatches.values()) {
            loadElementPatch(elementPatchFile);
        }

        Map<String, Resource> resources = ResourceLoaderUtils.loadFiles("classpath*:elements/**/description.{yml|yaml}");

        for (Map.Entry<String, Resource> dirPathToDescriptorFile : resources.entrySet()) {
            loadElement(dirPathToDescriptorFile.getKey(), dirPathToDescriptorFile.getValue());
        }
    }

    private void loadElementPatch(Resource elementPatchFile) {
        try {
            String elementName = Optional.ofNullable(elementPatchFile.getFilename())
                    .map(fileName -> fileName.substring(0, fileName.lastIndexOf(".")))
                    .orElse("unknown");
            if (log.isDebugEnabled()) {
                log.debug("Loading {} element part", elementName);
            }

            this.registry.loadElementPatch(elementName, elementPatchFile.getInputStream());
        } catch (IOException e) {
            log.error("Error loading {} element part", elementPatchFile.getFilename(), e);
        }
    }

    private void loadElement(String dirPath, Resource descriptorFile) {
        String elementName = null;
        try {
            int start = dirPath.lastIndexOf('/', dirPath.length() - 2) + 1;
            elementName = dirPath.substring(start);
            if (log.isDebugEnabled()) {
                log.debug("Processing element directory: {}", dirPath);
            }

            if (descriptorFile != null) {
                this.registry.loadElementDescriptor(elementName, descriptorFile.getInputStream());
            } else {
                log.warn("Descriptor file is missing for {}, skipping", elementName);
            }
        } catch (IOException e) {
            log.error("Error loading element descriptor {}", elementName, e);
        }
    }
}

