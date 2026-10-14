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

package org.qubership.integration.platform.engine.util;

/**
 * Formats a millisecond duration for a Gateway API {@code HTTPRouteTimeouts} field. The CRD
 * schema requires each unit run to be 1-5 digits ({@code ^([0-9]{1,5}(h|m|s|ms)){1,4}$}), so a
 * plain {@code <millis>ms} suffix is only valid up to 99999. Above that, the value is
 * decomposed into whole hours/minutes/seconds/milliseconds, each of which stays well under the
 * 5-digit limit for any duration a route timeout would realistically use.
 */
public final class GatewayDuration {
    private static final long MAX_PLAIN_MILLIS = 99_999;
    private static final long MILLIS_PER_HOUR = 3_600_000;
    private static final long MILLIS_PER_MINUTE = 60_000;
    private static final long MILLIS_PER_SECOND = 1_000;

    private GatewayDuration() {
    }

    public static String formatMillis(long millis) {
        if (millis <= MAX_PLAIN_MILLIS) {
            return millis + "ms";
        }

        long remainder = millis;
        long hours = remainder / MILLIS_PER_HOUR;
        remainder %= MILLIS_PER_HOUR;
        long minutes = remainder / MILLIS_PER_MINUTE;
        remainder %= MILLIS_PER_MINUTE;
        long seconds = remainder / MILLIS_PER_SECOND;
        remainder %= MILLIS_PER_SECOND;

        StringBuilder result = new StringBuilder();
        if (hours > 0) {
            result.append(hours).append("h");
        }
        if (minutes > 0) {
            result.append(minutes).append("m");
        }
        if (seconds > 0) {
            result.append(seconds).append("s");
        }
        if (remainder > 0) {
            result.append(remainder).append("ms");
        }
        return result.toString();
    }
}
