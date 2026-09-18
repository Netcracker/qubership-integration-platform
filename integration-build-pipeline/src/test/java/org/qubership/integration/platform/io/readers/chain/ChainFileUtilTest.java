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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainFileUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "chain-orders.yaml",
        "chain-orders.yml",
        "e2652c43-887d-4e58-a0b0-6c1d527c7741.chain.qip.yaml",
        "e2652c43-887d-4e58-a0b0-6c1d527c7741.chain.qip.yml"
    })
    void acceptsAChainExport(String fileName) {
        assertTrue(ChainFileUtil.isChainFile(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "chain-orders.json",
        "chain-orders.yaml.bak",
        "e2652c43-887d-4e58-a0b0-6c1d527c7741.chain.qip.json"
    })
    void rejectsAFileThatIsNotYaml(String fileName) {
        assertFalse(ChainFileUtil.isChainFile(fileName));
    }

    /**
     * {@code chain.yaml} carries neither marker. A chain export always names the chain, either as the
     * {@code chain-} prefix of a legacy export or as the {@code .chain.} segment of a current one.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "chain.yaml",
        "service-petstore.yaml",
        "1acc68e3-fa98-4b67-95a5-60e3e26ba744.service.qip.yaml",
        "script-0533ce79-4a44-4774-bed8-3fa73e1c051c.groovy",
        "orders.yaml"
    })
    void rejectsAnythingElseInTheSourceTree(String fileName) {
        assertFalse(ChainFileUtil.isChainFile(fileName));
    }
}
