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

package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasSession;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Function;

import org.qubership.integration.platform.engine.util.AtlasMapUtils;

public class TimestampGenerator implements Function<AtlasSession, String> {
    private final boolean isUnixEpoch;
    private final String format;
    private final String locale;
    private final String timezone;

    public TimestampGenerator(boolean isUnixEpoch, String format, String locale, String timezone) {
        this.isUnixEpoch = isUnixEpoch;
        this.format = format;
        this.locale = locale;
        this.timezone = timezone;
    }

    public static TimestampGenerator fromParameterList(List<String> parameters) {
        boolean isUnixEpoch = parameters.size() > 0 && Boolean.parseBoolean(parameters.get(0));
        String format = parameters.size() > 1 ? parameters.get(1) : "";
        String locale = parameters.size() > 2 ? parameters.get(2) : "";
        String timezone = parameters.size() > 3 ? parameters.get(3) : "";
        return new TimestampGenerator(isUnixEpoch, format, locale, timezone);
    }

    @Override
    public String apply(AtlasSession atlasSession) {
        String value = (String) atlasSession.getSourceProperties().get("Atlas.CreatedDateTimeTZ");
        return AtlasMapUtils.convertDateFormat(
                false,
                "yyyy-MM-dd'T'HH:mm:ssZ",
                Locale.getDefault(Locale.Category.FORMAT).toString(),
                TimeZone.getDefault().getID(),
                isUnixEpoch, format, locale, timezone, value);
    }
}
