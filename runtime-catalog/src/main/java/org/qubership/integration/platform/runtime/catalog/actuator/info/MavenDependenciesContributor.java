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

package org.qubership.integration.platform.runtime.catalog.actuator.info;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class MavenDependenciesContributor implements InfoContributor {

    private static final String DEPENDENCY_LIST_KEY = "dependency-list";

    private final Resource dependenciesResource;

    public MavenDependenciesContributor(Resource dependenciesResource) {
        this.dependenciesResource = dependenciesResource;
    }

    @Override
    public void contribute(Info.Builder builder) {
        try {
            List<String> dependencyList = Files.readAllLines(dependenciesResource.getFile().toPath()).stream()
                    .filter(line -> line.contains(":jar:"))
                    .map(String::trim)
                    .collect(Collectors.toList());
            builder.withDetail(DEPENDENCY_LIST_KEY, dependencyList);
        } catch (IOException e) {
            log.error("Error loading {}", dependenciesResource.getFilename(), e);
        }
    }
}
