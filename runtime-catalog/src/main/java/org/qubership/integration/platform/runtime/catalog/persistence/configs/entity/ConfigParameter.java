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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.qubership.integration.platform.runtime.catalog.model.ConfigParameterValueType;

import java.util.Base64;

@NoArgsConstructor
@Getter
@Table(uniqueConstraints = @UniqueConstraint(
        columnNames = {"namespace", "name"}, name = "uk_config_parameters_on_namespace_name"))
@Entity(name = "config_parameters")
public class ConfigParameter extends AbstractEntity {

    @NotBlank
    private String namespace;

    @Enumerated(EnumType.STRING)
    private ConfigParameterValueType valueType;

    @Column(columnDefinition = "TEXT")
    private String value;

    public ConfigParameter(String namespace, String name) {
        this.namespace = namespace;
        this.name = name;
    }

    private void setValueType(ConfigParameterValueType newValueType) {
        checkValueType(newValueType);

        this.valueType = newValueType;
        this.value = null;
    }

    public void checkValueType(ConfigParameterValueType newValueType) {
        if (newValueType == valueType) {
            return;
        }
        if (newValueType != null && valueType != null) {
            throw new IllegalStateException("Value type should be reset before type change");
        }
    }

    private void compareValueType(ConfigParameterValueType requestValueType) {
        if (valueType != requestValueType) {
            throw new ClassCastException("Value types didn't match!");
        }
    }

    public void resetValueType() {
        setValueType(null);
    }

    public void setString(String value) {
        setValueType(ConfigParameterValueType.STRING);
        this.value = value;
    }

    public String getString() {
        compareValueType(ConfigParameterValueType.STRING);
        return value;
    }

    public void setInt(int value) {
        setValueType(ConfigParameterValueType.INT);
        this.value = Integer.valueOf(value).toString();
    }

    public int getInt() {
        compareValueType(ConfigParameterValueType.INT);
        return Integer.parseInt(value);
    }

    public void setFloat(long value) {
        setValueType(ConfigParameterValueType.FLOAT);
        this.value = Float.valueOf(value).toString();
    }

    public float getFloat() {
        compareValueType(ConfigParameterValueType.FLOAT);
        return Float.parseFloat(value);
    }

    public void setBoolean(boolean value) {
        setValueType(ConfigParameterValueType.BOOLEAN);
        this.value = Boolean.valueOf(value).toString();
    }

    public boolean getBoolean() {
        compareValueType(ConfigParameterValueType.BOOLEAN);
        return Boolean.parseBoolean(value);
    }

    public void setByte(byte[] value) {
        setValueType(ConfigParameterValueType.BYTE);
        this.value = Base64.getEncoder().encodeToString(value);
    }

    public byte[] getByte() {
        compareValueType(ConfigParameterValueType.BYTE);
        return Base64.getDecoder().decode(value);
    }

    public boolean setRawValueIfTypeMatch(String newValue, ConfigParameterValueType newValueType) {
        String oldValue = value;
        ConfigParameterValueType oldValueType = valueType;
        valueType = newValueType;
        if (valueType == null) {
            value = null;
            return true;
        }
        value = newValue;

        try {
            switch (valueType) {
                case STRING:
                    getString();
                    break;
                case INT:
                    getInt();
                    break;
                case FLOAT:
                    getFloat();
                    break;
                case BOOLEAN:
                    getBoolean();
                    break;
                case BYTE:
                    getByte();
                    break;
            }
        } catch (RuntimeException e) {
            value = oldValue;
            valueType = oldValueType;
            return false;
        }

        if (valueType == ConfigParameterValueType.BOOLEAN) {
            setBoolean(getBoolean()); // Random string interpreting as false, let's store actual booleans true\false
        }

        return true;
    }
}
