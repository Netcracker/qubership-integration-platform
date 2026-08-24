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

package org.qubership.integration.platform.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaasConnectionSourceUtilsTest {

    @Test
    void shouldMatchMaasConnectionSourceCaseInsensitively() {
        assertTrue(MaasConnectionSourceUtils.isMaasConnectionSource("MAAS"));
        assertTrue(MaasConnectionSourceUtils.isMaasConnectionSource("maas"));
        assertTrue(MaasConnectionSourceUtils.isMaasConnectionSource("Maas"));
        assertFalse(MaasConnectionSourceUtils.isMaasConnectionSource("MANUAL"));
        assertFalse(MaasConnectionSourceUtils.isMaasConnectionSource(null));
    }

    @Test
    void shouldMatchMaasByClassifierConnectionSourceCaseInsensitively() {
        assertTrue(MaasConnectionSourceUtils.isMaasByClassifierConnectionSource("MAAS_BY_CLASSIFIER"));
        assertTrue(MaasConnectionSourceUtils.isMaasByClassifierConnectionSource("maas_by_classifier"));
        assertFalse(MaasConnectionSourceUtils.isMaasByClassifierConnectionSource("MAAS"));
    }

    @Test
    void shouldMatchEitherMaasConnectionSourceTypeCaseInsensitively() {
        assertTrue(MaasConnectionSourceUtils.isMaasOrMaasByClassifierConnectionSource("maas"));
        assertTrue(MaasConnectionSourceUtils.isMaasOrMaasByClassifierConnectionSource("MAAS_BY_CLASSIFIER"));
        assertFalse(MaasConnectionSourceUtils.isMaasOrMaasByClassifierConnectionSource("manual"));
    }
}
