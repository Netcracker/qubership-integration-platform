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

package org.qubership.integration.platform.io.readers.system;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceFileUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "service-petstore.yaml",
        "service-petstore.yml",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.service.qip.yaml",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.service.qip.yml"
    })
    void acceptsAnIntegrationSystemExport(String fileName) {
        assertTrue(ServiceFileUtil.isIntegrationSystemFile(fileName));
    }

    /**
     * Context services and MCP services have their own readers and no part in resource generation.
     * Matching them here fed them to {@code IntegrationSystemReader}, which parsed them as integration
     * systems and registered the result in the service catalog.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "context-service-orders.yaml",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.context-service.qip.yaml",
        "mcp-service-orders.yaml",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.mcp-service.qip.yaml"
    })
    void rejectsAContextOrMcpServiceExport(String fileName) {
        assertFalse(ServiceFileUtil.isIntegrationSystemFile(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "service-petstore.json",
        "service-petstore.yaml.bak",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.service.qip.json"
    })
    void rejectsAFileThatIsNotYaml(String fileName) {
        assertFalse(ServiceFileUtil.isIntegrationSystemFile(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "chain-orders.yaml",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.chain.qip.yaml",
        "petstore.specification.qip.yaml",
        "petstore.specification-group.qip.yaml",
        "myservice.yaml"
    })
    void rejectsAnythingElseInTheSourceTree(String fileName) {
        assertFalse(ServiceFileUtil.isIntegrationSystemFile(fileName));
    }
}
