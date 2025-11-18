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
import org.qubership.integration.platform.engine.util.MapperUtils;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MapperUtilsTest {

    @Test void shouldReturnNullWhenFromTimestampReceivesNull() {
        assertNull(MapperUtils.fromTimestamp(null));
    }

    @Test void shouldReturnNullWhenToTimestampReceivesNull() {
        assertNull(MapperUtils.toTimestamp(null));
    }

    @Test void shouldReturnSameMillisWhenFromTimestampHasZeroNanos() {
        long ms = 1_696_000_000_123L;
        Timestamp ts = new Timestamp(ms);
        assertEquals(ms, MapperUtils.fromTimestamp(ts));
    }

    @Test void shouldRoundTripMillisOnly() {
        long ms = 42L;
        Long back = MapperUtils.fromTimestamp(MapperUtils.toTimestamp(ms));
        assertEquals(ms, back);
    }

    @Test void shouldDropSubMilliSecondNanosWhenMappingToLong() {
        long baseMs = 1_000L;
        Timestamp ts = new Timestamp(baseMs);
        ts.setNanos(999_999);
        assertEquals(baseMs, MapperUtils.fromTimestamp(ts));
    }

    @Test void shouldIncludeWholeMillisecondsFromNanosInGetTime() {
        long baseMs = 1_650_000_000_000L;
        Timestamp ts = new Timestamp(baseMs);
        ts.setNanos(987_654_321);
        assertEquals(baseMs + 987, MapperUtils.fromTimestamp(ts));
    }
}
