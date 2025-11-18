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

package org.qubership.integration.platform.engine.unit.utils.log;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLogger;
import org.qubership.integration.platform.engine.util.log.ExtendedErrorLoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ExtendedErrorLoggerFactoryTest {

    @Test
    void shouldCreateNewInstanceEachTimeWhenGetLoggerByName() {
        ExtendedErrorLogger a = ExtendedErrorLoggerFactory.getLogger("x");
        ExtendedErrorLogger b = ExtendedErrorLoggerFactory.getLogger("x");
        assertNotSame(a, b);
    }


    @Test
    void shouldCreateLoggerForClassWhenGetLoggerByClass() {
        ExtendedErrorLogger log = ExtendedErrorLoggerFactory.getLogger(Integer.class);
        assertNotNull(log);
    }
}
