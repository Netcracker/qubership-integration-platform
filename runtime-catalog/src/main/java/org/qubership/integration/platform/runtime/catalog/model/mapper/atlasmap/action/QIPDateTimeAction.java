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

import java.io.Serializable;

public class QIPDateTimeAction extends QIPCustomAction implements Serializable {

    @JsonProperty("returnUnixTimeOutput")
    private Boolean returnUnixTimeOutput;

    @JsonProperty("outputFormat")
    private String outputFormat;

    @JsonProperty("outputTimezone")
    private String outputTimezone;

    @JsonProperty("outputLocale")
    private String outputLocale;


    public QIPDateTimeAction() {
    }

    public QIPDateTimeAction(QIPCustomActionType type) {
        super(type);
    }

    public QIPDateTimeAction(QIPCustomActionType type, Boolean returnUnixTimeOutput, String outputFormat, String outputTimezone, String outputLocale) {
        super(type);
        this.returnUnixTimeOutput = returnUnixTimeOutput;
        this.outputFormat = outputFormat;
        this.outputTimezone = outputTimezone;
        this.outputLocale = outputLocale;
    }

    @JsonProperty("returnUnixTimeOutput")
    public Boolean getReturnUnixTimeOutput() {
        return returnUnixTimeOutput;
    }

    @JsonSetter("returnUnixTimeOutput")
    public void setReturnUnixTimeOutput(Boolean returnUnixTimeOutput) {
        this.returnUnixTimeOutput = returnUnixTimeOutput;
    }

    @JsonProperty("outputFormat")
    public String getOutputFormat() {
        return outputFormat;
    }

    @JsonSetter("outputFormat")
    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    @JsonProperty("outputTimezone")
    public String getOutputTimezone() {
        return outputTimezone;
    }

    @JsonSetter("outputTimezone")
    public void setOutputTimezone(String outputTimezone) {
        this.outputTimezone = outputTimezone;
    }

    @JsonProperty("outputLocale")
    public String getOutputLocale() {
        return outputLocale;
    }

    @JsonSetter("outputLocale")
    public void setOutputLocale(String outputLocale) {
        this.outputLocale = outputLocale;
    }

}
