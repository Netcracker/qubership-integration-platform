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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.atlasmap.spi.AtlasActionProcessor;
import io.atlasmap.spi.AtlasFieldAction;
import io.atlasmap.v2.Action;
import io.atlasmap.v2.AtlasActionProperty;
import io.atlasmap.v2.FieldType;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.isNull;

public class AtlasMapUtils implements AtlasFieldAction {

    public static class TemporalAccessorWithDefaultTimeAndZone implements TemporalAccessor {
        private final TemporalAccessor delegate;

        public TemporalAccessorWithDefaultTimeAndZone(TemporalAccessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isSupported(TemporalField field) {
            return delegate.isSupported(field);
        }

        @Override
        public long getLong(TemporalField field) {
            return delegate.getLong(field);
        }

        @Override
        public ValueRange range(TemporalField field) {
            return delegate.range(field);
        }

        @Override
        public int get(TemporalField field) {
            return delegate.get(field);
        }

        @Override
        public <R> R query(TemporalQuery<R> query) {
            R result = delegate.query(query);
            if (isNull(result)) {
                if (query == TemporalQueries.zone() || query == TemporalQueries.zoneId()) {
                    return (R) ZoneId.of("UTC");
                } else if (query == TemporalQueries.localTime()) {
                    return (R) LocalTime.of(0, 0);
                } else {
                    return null;
                }
            }
            return result;
        }
    }

    public static class QIPGetUUID extends Action implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public static class QIPDateTimeAction extends Action implements Serializable {
        private static final long serialVersionUID = 1L;

        protected Boolean returnUnixTimeOutput;

        protected String outputFormat;

        protected String outputLocale;

        protected String outputTimezone;

        public String getOutputFormat() {
            return outputFormat;
        }

        public boolean getReturnUnixTimeOutput() {
            return returnUnixTimeOutput;
        }

        @JsonPropertyDescription("Define output date as unix time")
        @AtlasActionProperty(title = "returnUnixTimeOutput", type = FieldType.BOOLEAN)
        public void setReturnUnixTimeOutput(Boolean returnUnixTime) {
            this.returnUnixTimeOutput = returnUnixTime;
        }

        @JsonPropertyDescription("Define output format")
        @AtlasActionProperty(title = "outputFormat", type = FieldType.STRING)
        public void setOutputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
        }

        public String getOutputLocale() {
            return outputLocale;
        }

        @JsonPropertyDescription("Define output locale")
        @AtlasActionProperty(title = "outputlocale", type = FieldType.STRING)
        public void setOutputLocale(String outputLocale) {
            this.outputLocale = outputLocale;
        }

        public String getOutputTimezone() {
            return outputTimezone;
        }

        @JsonPropertyDescription("Define output timezone")
        @AtlasActionProperty(title = "outputTimezone", type = FieldType.STRING)
        public void setOutputTimezone(String outputTimezone) {
            this.outputTimezone = outputTimezone;
        }

    }

    public static class QIPCurrentTime extends QIPDateTimeAction implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public static class QIPCurrentDate extends QIPDateTimeAction implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public static class QIPCurrentDateTime extends QIPDateTimeAction implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public static class QIPDefaultValue extends Action implements Serializable {
        private static final long serialVersionUID = 1L;

        protected Object defaultValue = "";

        public Object getDefaultValue() {
            return defaultValue;
        }

        @JsonPropertyDescription("Define default value if input field is empty")
        @AtlasActionProperty(title = "defaultValue", type = FieldType.ANY)
        public void setDefaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    public static class QIPFormatDateTime extends QIPDateTimeAction implements Serializable {
        private static final long serialVersionUID = 1L;

        protected Boolean returnUnixTimeInput;
        protected String inputFormat;
        protected String inputLocale;
        protected String inputTimezone;

        public Boolean getReturnUnixTimeInput() {
            return returnUnixTimeInput;
        }

        @JsonPropertyDescription("Define input date as unix time")
        @AtlasActionProperty(title = "returnUnixTimeInput", type = FieldType.BOOLEAN)
        public void setReturnUnixTimeInput(Boolean returnUnixTimeInput) {
            this.returnUnixTimeInput = returnUnixTimeInput;
        }

        public String getInputFormat() {
            return inputFormat;
        }

        @JsonPropertyDescription("Define input format")
        @AtlasActionProperty(title = "inputFormat", type = FieldType.STRING)
        public void setInputFormat(String inputFormat) {
            this.inputFormat = inputFormat;
        }

        public String getInputLocale() {
            return inputLocale;
        }

        @JsonPropertyDescription("Define input locale")
        @AtlasActionProperty(title = "inputLocale", type = FieldType.STRING)
        public void setInputLocale(String inputLocale) {
            this.inputLocale = inputLocale;
        }

        public String getInputTimezone() {
            return inputTimezone;
        }

        @JsonPropertyDescription("Define input timezone")
        @AtlasActionProperty(title = "inputTimezone", type = FieldType.STRING)
        public void setInputTimezone(String inputTimezone) {
            this.inputTimezone = inputTimezone;
        }

    }

    public static class QIPDictionary extends Action implements Serializable {

        protected String defaultValue = "";

        protected Map<String, String> dictionary = new HashMap<>();


        public String getDefaultValue() {
            return defaultValue;
        }

        @JsonPropertyDescription("Define default value if no dictionary rule found")
        @AtlasActionProperty(title = "defaultValue", type = FieldType.ANY)
        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public Map<String, String> getDictionary() {
            return dictionary;
        }

        @JsonPropertyDescription("Define dictionary rules")
        @AtlasActionProperty(title = "dictionary", type = FieldType.ANY)
        public void setDictionary(Map<String, String> dictionary) {
            this.dictionary = dictionary;
        }
    }

    @AtlasActionProcessor(sourceType = FieldType.ANY)
    public static String getuuid(QIPGetUUID action, String input) {
        return UUID.randomUUID().toString();
    }

    @AtlasActionProcessor(sourceType = FieldType.ANY)
    public static String currentTime(QIPCurrentTime action, String input) {
        return convertDateFormat(
                action.getReturnUnixTimeOutput(),
                action.getOutputFormat(),
                action.getOutputTimezone(),
                action.getOutputLocale(),
                Instant.now().getEpochSecond()
        );
    }

    @AtlasActionProcessor(sourceType = FieldType.ANY)
    public static String currentDate(QIPCurrentDate action, String input) {
        return convertDateFormat(
                action.getReturnUnixTimeOutput(),
                action.getOutputFormat(),
                action.getOutputTimezone(),
                action.getOutputLocale(),
                Instant.now().getEpochSecond()
        );
    }

    @AtlasActionProcessor(sourceType = FieldType.ANY)
    public static String currentDateTime(QIPCurrentDateTime action, String input) {
        return convertDateFormat(
                action.getReturnUnixTimeOutput(),
                action.getOutputFormat(),
                action.getOutputTimezone(),
                action.getOutputLocale(),
                Instant.now().getEpochSecond()
        );
    }

    @AtlasActionProcessor(sourceType = FieldType.ANY)
    public static Object defaultValue(QIPDefaultValue action, Object input) {
        if (input instanceof String) {
            input = ((String) input).isBlank() ? null : input;
        }
        return input == null ? action.getDefaultValue() : input;
    }

    @AtlasActionProcessor(sourceType = FieldType.STRING)
    public static String formatDateTime(QIPFormatDateTime action, String input) {
        return convertDateFormat(
                action.getReturnUnixTimeInput(),
                action.getInputFormat(),
                action.getInputLocale(),
                action.getInputTimezone(),
                action.getReturnUnixTimeOutput(),
                action.getOutputFormat(),
                action.getOutputLocale(),
                action.getOutputTimezone(),
                input
        );
    }

    @AtlasActionProcessor(sourceType = FieldType.STRING)
    public static String lookupValue(QIPDictionary action, String input) {
        return action.getDictionary().getOrDefault(input, action.defaultValue);
    }

    private static String convertDateFormat(Boolean returnUnixTimeOutput, String outputFormat, String outputLocale, String outputTimezone, long input) {
        if (returnUnixTimeOutput) {
            return String.valueOf(input);
        }

        return convertDateFormat(
                true,
                outputFormat,
                outputLocale,
                outputTimezone,
                false,
                outputFormat,
                outputLocale,
                outputTimezone,
                String.valueOf(input)
        );
    }

    public static String convertDateFormat(Boolean returnUnixTimeInput, String inputFormat, String inputLocale, String inputTimezone,
                                            Boolean returnUnixTimeOutput, String outputFormat, String outputLocale, String outputTimezone,
                                            String input) {

        DateTimeFormatter inputFormatter = getTimeFormatter(inputFormat, inputLocale, inputTimezone);
        DateTimeFormatter outputFormatter = getTimeFormatter(outputFormat, outputLocale, outputTimezone);

        if (returnUnixTimeInput) {
            return returnUnixTimeOutput ? input : Instant.ofEpochSecond(Long.parseLong(input))
                                                    .atZone(ZoneId.of("UTC"))
                                                    .format(outputFormatter);
        }

        TemporalAccessor accessor = new TemporalAccessorWithDefaultTimeAndZone(inputFormatter.parse(input));
        ZonedDateTime zonedDateTime = ZonedDateTime.from(accessor);

        if (returnUnixTimeOutput) {
            Instant instant = Instant.from(zonedDateTime);
            long epochSeconds = instant.toEpochMilli() / 1000;
            return String.valueOf(epochSeconds);
        }

        return outputFormatter.format(zonedDateTime);
    }

    private static DateTimeFormatter getTimeFormatter(String pattern, String localeAsString, String timeZoneAsString) {

        DateTimeFormatter dateTimeFormatter = pattern.isBlank()
                ? DateTimeFormatter.BASIC_ISO_DATE
                : DateTimeFormatter.ofPattern(pattern);

        if (!timeZoneAsString.isBlank()) {
            ZoneId zoneId = ZoneId.of(timeZoneAsString);
            dateTimeFormatter = dateTimeFormatter.withZone(zoneId);
        }

        if (!localeAsString.isBlank()) {
            String[] localeComponents = localeAsString.split("_");
            Locale locale = new Locale.Builder()
                    .setLanguage(localeComponents[0])
                    .setRegion(localeComponents[1])
                    .build();
            dateTimeFormatter = dateTimeFormatter.withLocale(locale);
        } else {
            dateTimeFormatter = dateTimeFormatter.withLocale(Locale.US);
        }

        return dateTimeFormatter;
    }

    public static String getQueryParameters(String uri) {
        return uri.contains("?") ? uri.split("\\?", 2)[1] : "";
    }
}
