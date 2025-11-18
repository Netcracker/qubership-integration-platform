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
import org.qubership.integration.platform.engine.util.AtlasMapUtils;

import java.time.ZoneId;
import java.time.temporal.TemporalQueries;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class AtlasMapUtilsTest {

    @Test
    void shouldReturnUuidStringForGetUUID() {
        String uuid1 = AtlasMapUtils.getuuid(new AtlasMapUtils.QIPGetUUID(), null);
        String uuid2 = AtlasMapUtils.getuuid(new AtlasMapUtils.QIPGetUUID(), null);
        assertDoesNotThrow(() -> UUID.fromString(uuid1));
        assertDoesNotThrow(() -> UUID.fromString(uuid2));
        assertNotEquals(uuid1, uuid2);
    }

    @Test
    void shouldReturnDefaultWhenInputIsNullOrBlank() {
        AtlasMapUtils.QIPDefaultValue action = new AtlasMapUtils.QIPDefaultValue();
        action.setDefaultValue("DEF");
        assertEquals("DEF", AtlasMapUtils.defaultValue(action, null));
        assertEquals("DEF", AtlasMapUtils.defaultValue(action, ""));
        assertEquals("DEF", AtlasMapUtils.defaultValue(action, "   "));
    }

    @Test
    void shouldReturnOriginalWhenInputNonBlankOrNonString() {
        AtlasMapUtils.QIPDefaultValue action = new AtlasMapUtils.QIPDefaultValue();
        action.setDefaultValue("DEF");
        assertEquals("abc", AtlasMapUtils.defaultValue(action, "abc"));
        assertEquals(123, AtlasMapUtils.defaultValue(action, 123));
    }

    @Test
    void shouldLookupValueFromDictionaryOrDefault() {
        AtlasMapUtils.QIPDictionary action = new AtlasMapUtils.QIPDictionary();
        Map<String, String> dict = new HashMap<>();
        dict.put("A", "Alpha");
        dict.put("B", "Beta");
        action.setDictionary(dict);
        action.setDefaultValue("N/A");
        assertEquals("Alpha", AtlasMapUtils.lookupValue(action, "A"));
        assertEquals("Beta", AtlasMapUtils.lookupValue(action, "B"));
        assertEquals("N/A", AtlasMapUtils.lookupValue(action, "C"));
    }

    @Test
    void shouldConvertEpochToFormattedDateWhenUnixInputAndFormattedOutput() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(true);
        action.setInputFormat("");
        action.setInputLocale("");
        action.setInputTimezone("");
        action.setReturnUnixTimeOutput(false);
        action.setOutputFormat("yyyy-MM-dd HH:mm:ss");
        action.setOutputLocale("");
        action.setOutputTimezone("UTC");
        assertEquals("1970-01-01 00:00:00", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "0"
        ));
    }

    @Test
    void shouldConvertFormattedDateToEpochWhenFormattedInputAndUnixOutput() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(false);
        action.setInputFormat("yyyy-MM-dd HH:mm:ss");
        action.setInputLocale("");
        action.setInputTimezone("UTC");
        action.setReturnUnixTimeOutput(true);
        action.setOutputFormat("");
        action.setOutputLocale("");
        action.setOutputTimezone("");
        assertEquals("86400", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "1970-01-02 00:00:00"
        ));
    }

    @Test
    void shouldUseBasicIsoDateWhenFormatsAreBlank() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(false);
        action.setInputFormat("");
        action.setInputLocale("");
        action.setInputTimezone("");
        action.setReturnUnixTimeOutput(false);
        action.setOutputFormat("");
        action.setOutputLocale("");
        action.setOutputTimezone("");
        assertEquals("20240501Z", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "20240501"
        ));
    }

    @Test
    void shouldDefaultLocaleUsWhenLocaleBlank() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(false);
        action.setInputFormat("MMM dd, uuuu");
        action.setInputLocale("");
        action.setInputTimezone("UTC");
        action.setReturnUnixTimeOutput(false);
        action.setOutputFormat("yyyy-MM-dd");
        action.setOutputLocale("");
        action.setOutputTimezone("UTC");
        assertEquals("2024-05-01", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "May 01, 2024"
        ));
    }

    @Test
    void shouldDefaultTimeAndZoneWhenMissingInInput() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(false);
        action.setInputFormat("yyyy-MM-dd");
        action.setInputLocale("");
        action.setInputTimezone("");
        action.setReturnUnixTimeOutput(false);
        action.setOutputFormat("HH:mm VV");
        action.setOutputLocale("");
        action.setOutputTimezone("UTC");
        assertEquals("00:00 UTC", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "2024-05-01"
        ));
    }

    @Test
    void shouldKeepSameLocalMidnightWhenTimezonesAreEqual() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(false);
        action.setInputFormat("yyyy-MM-dd");
        action.setInputLocale("");
        action.setInputTimezone("Europe/Amsterdam");
        action.setReturnUnixTimeOutput(false);
        action.setOutputFormat("yyyy-MM-dd HH:mm");
        action.setOutputLocale("");
        action.setOutputTimezone("Europe/Amsterdam");
        assertEquals("2024-05-10 00:00", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "2024-05-10"
        ));
    }

    @Test
    void shouldReturnSameUnixStringWhenBothUnixFlagsTrue() {
        AtlasMapUtils.QIPFormatDateTime action = new AtlasMapUtils.QIPFormatDateTime();
        action.setReturnUnixTimeInput(true);
        action.setInputFormat("");
        action.setInputLocale("");
        action.setInputTimezone("");
        action.setReturnUnixTimeOutput(true);
        action.setOutputFormat("");
        action.setOutputLocale("");
        action.setOutputTimezone("");
        assertEquals("1714521600", AtlasMapUtils.convertDateFormat(
                action.getReturnUnixTimeInput(), action.getInputFormat(), action.getInputLocale(), action.getInputTimezone(),
                action.getReturnUnixTimeOutput(), action.getOutputFormat(), action.getOutputLocale(), action.getOutputTimezone(),
                "1714521600"
        ));
    }

    @Test
    void shouldExposeDefaultsFromTemporalWrapper() {
        var base = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").parse("2024-11-18");
        var wrapped = new AtlasMapUtils.TemporalAccessorWithDefaultTimeAndZone(base);
        assertEquals(ZoneId.of("UTC"), wrapped.query(TemporalQueries.zone()));
        assertEquals(ZoneId.of("UTC"), wrapped.query(TemporalQueries.zoneId()));
        assertEquals(java.time.LocalTime.MIDNIGHT, wrapped.query(TemporalQueries.localTime()));
        assertNotNull(wrapped.query(TemporalQueries.localDate()));
    }

    @Test
    void shouldExtractQueryParametersWhenPresent() {
        assertEquals("a=1&b=2", AtlasMapUtils.getQueryParameters("http://host/path?a=1&b=2"));
        assertEquals("", AtlasMapUtils.getQueryParameters("http://host/path"));
        assertEquals("", AtlasMapUtils.getQueryParameters("/x?"));
    }
}
