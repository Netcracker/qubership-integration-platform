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

package org.qubership.integration.platform.sessions.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeUtilsTest {

    private TimeZone original;

    @BeforeEach
    void setUp() {
        original = TimeZone.getDefault();
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(original);
    }

    @Test
    void shouldConvertToMillisWhenUtcTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        LocalDateTime t0 = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
        LocalDateTime t1 = LocalDateTime.of(1970, 1, 1, 0, 0, 1);
        assertEquals(0L, TimeUtils.toMillis(t0));
        assertEquals(1000L, TimeUtils.toMillis(t1));
    }

    @Test
    void shouldComputeDifferenceInMillisWhenUtcTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        LocalDateTime a = LocalDateTime.of(2024, 5, 1, 0, 0, 2);
        LocalDateTime b = LocalDateTime.of(2024, 5, 1, 0, 0, 0);
        assertEquals(2000L, TimeUtils.subtract(a, b));
        assertEquals(-2000L, TimeUtils.subtract(b, a));
    }

    @Test
    void shouldRespectSystemTimezoneWhenNonUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"));
        LocalDateTime t = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
        assertEquals(-3_600_000L, TimeUtils.toMillis(t));
    }
}
