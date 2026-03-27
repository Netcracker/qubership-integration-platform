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

import java.util.HashMap;
import java.util.Map;

public class QIPDictionaryAction extends QIPCustomAction {
    //TODO Extend from QIPDefaultValue Action
    @JsonProperty("defaultValue")
    private String defaultValue = "";

    @JsonProperty("dictionary")
    private Map<String, String> dictionary = new HashMap<>();


    public QIPDictionaryAction() {
       super(QIPCustomActionType.DICTIONARY);
    }

    public QIPDictionaryAction(String defaultValue, Map<String, String> dictionary) {
        super(QIPCustomActionType.DICTIONARY);
        this.defaultValue = defaultValue;
        this.dictionary = dictionary;
    }

    @JsonProperty("defaultValue")
    public String getDefaultValue() {
        return defaultValue;
    }

    @JsonSetter("defaultValue")
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    @JsonProperty("dictionary")
    public Map<String, String> getDictionary() {
        return dictionary;
    }

    @JsonSetter("dictionary")
    public void setDictionary(Map<String, String> dictionary) {
        this.dictionary = dictionary;
    }
}
