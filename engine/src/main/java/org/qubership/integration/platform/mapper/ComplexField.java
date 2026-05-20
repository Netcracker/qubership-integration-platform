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

package org.qubership.integration.platform.mapper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.atlasmap.v2.AtlasModelFactory;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.FieldType;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

public class ComplexField extends Field {
    @Serial
    private static final long serialVersionUID = 1L;

    private final List<Field> childFields;

    public ComplexField() {
        super();
        this.childFields = new ArrayList<>();
    }

    public ComplexField(List<Field> childFields) {
        this.childFields = childFields;
    }

    public List<Field> getChildFields() {
        return childFields;
    }

    public FieldGroup asFieldGroup() {
        FieldGroup group = AtlasModelFactory.createFieldGroupFrom(this, true);
        group.setFieldType(FieldType.COMPLEX);
        group.getField().addAll(childFields);
        group.setValue(getValue());
        return group;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return this.childFields.isEmpty();
    }
}
