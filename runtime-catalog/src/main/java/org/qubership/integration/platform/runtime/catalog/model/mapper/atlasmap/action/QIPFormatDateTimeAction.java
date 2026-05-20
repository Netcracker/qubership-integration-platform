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

package org.qubership.integration.platform.runtime.catalog.model.mapper.atlasmap.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;


public class QIPFormatDateTimeAction extends QIPDateTimeAction {

    @JsonProperty("returnUnixTimeInput")
    private Boolean returnUnixTimeInput;

    @JsonProperty("inputFormat")
    private String inputFormat;

    @JsonProperty("inputLocale")
    private String inputLocale;

    @JsonProperty("inputTimezone")
    private String inputTimezone;

    public QIPFormatDateTimeAction() {
    }

    public QIPFormatDateTimeAction(Boolean returnUnixTimeInput, String inputFormat, String inputLocale, String inputTimezone) {
        super(QIPCustomActionType.FORMAT_DATE_TIME);
        this.returnUnixTimeInput = returnUnixTimeInput;
        this.inputFormat = inputFormat;
        this.inputLocale = inputLocale;
        this.inputTimezone = inputTimezone;
    }

    public QIPFormatDateTimeAction(Boolean returnUnixTimeInput, String inputFormat, String inputLocale, String inputTimezone, Boolean returnUnixTimeOutput, String outputFormat, String outputLocale, String outputTimezone) {
        super(QIPCustomActionType.FORMAT_DATE_TIME, returnUnixTimeOutput, outputFormat, outputTimezone, outputLocale);
        this.returnUnixTimeInput = returnUnixTimeInput;
        this.inputFormat = inputFormat;
        this.inputLocale = inputLocale;
        this.inputTimezone = inputTimezone;
    }

    @JsonProperty("returnUnixTimeInput")
    public Boolean getReturnUnixTimeInput() {
        return returnUnixTimeInput;
    }

    @JsonSetter("returnUnixTimeInput")
    public void setReturnUnixTimeInput(Boolean returnUnixTimeInput) {
        this.returnUnixTimeInput = returnUnixTimeInput;
    }

    @JsonProperty("inputFormat")
    public String getInputFormat() {
        return inputFormat;
    }

    @JsonSetter("inputFormat")
    public void setInputFormat(String inputFormat) {
        this.inputFormat = inputFormat;
    }

    @JsonProperty("inputLocale")
    public String getInputLocale() {
        return inputLocale;
    }

    @JsonSetter("inputLocale")
    public void setInputLocale(String inputLocale) {
        this.inputLocale = inputLocale;
    }

    @JsonProperty("inputTimezone")
    public String getInputTimezone() {
        return inputTimezone;
    }

    @JsonSetter("inputTimezone")
    public void setInputTimezone(String inputTimezone) {
        this.inputTimezone = inputTimezone;
    }
}
