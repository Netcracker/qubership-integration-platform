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

package org.qubership.integration.platform.engine.unit.utils;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.DevModeUtil;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DevModeUtilTest {

    @Test
    void shouldReturnTrueWhenProfileIsDev() throws Exception {
        DevModeUtil util = new DevModeUtil();
        set(util, "activeProfile", "dev");
        assertTrue(util.isDevMode());
    }

    @Test
    void shouldReturnFalseWhenProfileIsNotDev() throws Exception {
        DevModeUtil util = new DevModeUtil();
        set(util, "activeProfile", "prod");
        assertFalse(util.isDevMode());
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
