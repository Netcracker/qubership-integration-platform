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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.atlasmap.v2.Action;

import java.io.Serializable;


public class QIPCustomAction extends Action implements Serializable {

    @JsonIgnore
    private QIPCustomActionType qipActionType;

    public QIPCustomAction() {}

    public QIPCustomAction(QIPCustomActionType type) {
        this.qipActionType = type;
    }

    @Override
    @JsonProperty("@type")
    public String getType() {
        return qipActionType.value();
    }
}
