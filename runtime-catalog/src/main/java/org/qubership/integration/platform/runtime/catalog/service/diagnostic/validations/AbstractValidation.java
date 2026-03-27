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

package org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations;

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationAlert;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Getter
public abstract class AbstractValidation {
    private static final Pattern ID_VALIDATION_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final String id;
    private final String title;
    private final String description;
    private final String suggestion;
    private final ValidationEntityType entityType;
    private final ValidationImplementationType implementationType;
    private final ValidationSeverity severity;

    private final Map<String, Serializable> properties = new HashMap<>();

    /**
     * @param id - must be a unique constant
     */
    public AbstractValidation(String id, String title, String description, String suggestion,
                              ValidationEntityType entityType, ValidationImplementationType implementationType,
                              ValidationSeverity severity) {
        if (!ID_VALIDATION_PATTERN.matcher(id).find()) {
            throw new IllegalArgumentException("Validation has invalid ID: " + id
                    + ". ID must match the pattern: " + ID_VALIDATION_PATTERN.pattern());
        }
        this.id = id;
        this.title = title;
        this.description = description;
        this.suggestion = suggestion;
        this.entityType = entityType;
        this.implementationType = implementationType;
        this.severity = severity;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public abstract Collection<? extends ValidationAlert> validate() throws DiagnosticValidationUnexpectedException;

    public void putProperties(Map<String, Serializable> props) {
        properties.putAll(props);
    }

    public void putProperty(String key, Serializable value) {
        properties.put(key, value);
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }
}
