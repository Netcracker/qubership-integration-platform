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

package org.qubership.integration.platform.engine.testutils;

import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class DeploymentUtils {
    private DeploymentUtils() {}

    public static DeploymentUpdate getDeploymentUpdate(String filePath) throws Exception {
        return deploymentFromXml(loadXml(filePath));
    }

    public static String loadXml(String classpath) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IllegalArgumentException("No resource: " + classpath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static DeploymentUpdate deploymentFromXml(String xml) {
        DeploymentInfo info = DeploymentInfo.builder()
                .chainId(UUID.randomUUID().toString())
                .deploymentId(UUID.randomUUID().toString())
                .chainName("test-chain")
                .snapshotId(UUID.randomUUID().toString())
                .createdWhen(System.currentTimeMillis())
                .build();

        DeploymentConfiguration cfg = DeploymentConfiguration.builder()
                .xml(xml)
                .properties(List.of())
                .routes(List.of())
                .build();

        return DeploymentUpdate.builder()
                .deploymentInfo(info)
                .configuration(cfg)
                .maskedFields(Collections.emptySet())
                .build();
    }
}
