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

package org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.components;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaObject {
    private String title;
    private String type;
    private List<String> required;
    private Number multipleOf;
    private Number minimum;
    private Number exclusiveMinimum;
    private Number maximum;
    private Number exclusiveMaximum;
    private Number minLength;
    private Number maxLength;
    private String pattern;
    private Number minItems;
    private Number maxItems;
    private Boolean uniqueItems;
    private Number minProperties;
    private Number maxProperties;
    @JsonProperty("enum")
    private List<String> enums;
    @JsonProperty("const")
    private String constant;
    private List<Object> examples;
    @JsonProperty("if")
    private Object ifClause;
    @JsonProperty("then")
    private Object thenClause;
    @JsonProperty("else")
    private Object elseClause;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean readOnly;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean writeOnly;
    @SuppressWarnings("checkstyle:MemberName")
    private String $ref;
    private List<Map<String, Object>> oneOf;
    private List<Map<String, Object>> allOf;
    private List<Map<String, Object>> anyOf;
    private List<Map<String, Object>> not;
    private Map<String, Object> properties;
    private Map<String, Object> propertyNames;
    private Map<String, Object> patternProperties;
    private Map<String, Object> additionalProperties;
    private Object items;
    private Object additionalItems;
    private Map<String, String> contains;
}
