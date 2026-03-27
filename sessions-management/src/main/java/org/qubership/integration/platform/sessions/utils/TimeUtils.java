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

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class TimeUtils {

    private TimeUtils() {}

    public static long subtract(LocalDateTime first, LocalDateTime second) {
        var firstMillis = toMillis(first);
        var secondMillis = toMillis(second);
        return firstMillis - secondMillis;
    }

    public static long toMillis(LocalDateTime time) {
        var zoneId = ZoneId.systemDefault();
        return time.atZone(zoneId)
                .toInstant()
                .toEpochMilli();
    }

}
