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

package org.qubership.integration.platform.runtime.catalog.model.system;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceEnvironment implements Cloneable, Serializable {
    private String id;
    private String systemId;
    private EnvironmentSourceType sourceType = EnvironmentSourceType.MANUAL;
    private String name;
    private String description;
    private String address;
    private Map<String, Object> properties;
    private boolean notActivated;
    private Long createdWhen;
    private Long modifiedWhen;

    @Override
    public ServiceEnvironment clone() {
        try {
            ServiceEnvironment clone = (ServiceEnvironment) super.clone();
            if (properties != null) {
                clone.setProperties(new HashMap<>(properties));
            }

            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
