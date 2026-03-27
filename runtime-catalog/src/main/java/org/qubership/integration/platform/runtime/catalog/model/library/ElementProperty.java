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

package org.qubership.integration.platform.runtime.catalog.model.library;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Data
@Schema(description = "Element property definition")
public class ElementProperty {
    /** Unique name of element property. */
    @Schema(description = "Unique name of element property")
    private String name;

    /** Displayed name of element property. */
    @Schema(description = "Displayed name of element property")
    private String title;

    /** Element property description. */
    @Schema(description = "Element property description")
    private String description;

    /** Parameter value type. */
    @Schema(description = "Parameter value type")
    private PropertyValueType type = PropertyValueType.STRING;

    @Schema(description = "Custom ui component needed to show this property")
    private String uiComponent;

    /** Parameter default value. */
    @JsonProperty("default")
    @Schema(description = "Parameter default value")
    private String defaultValue;

    /**
     * Can be useful when using some placeholders in {@link ElementProperty#defaultValue} (e.g. element id)
     * that need to be updated when an element is copied.
     * This option <b>must only be used with readonly properties</b>.
     */
    @Schema(description = "Whether reset property value to default on copy element process is needed")
    private boolean resetValueOnCopy = false;

    /** Parameter value mask. */
    @Schema(description = "Parameter value mask (if needed)")
    private Pattern mask;

    /** Indicates whether property value should be unique among element siblings of same type. */
    @Schema(description = "Indicates whether property value should be unique among element siblings of same type")
    private boolean unique = false;

    /** Alters value uniqueness check. Property value should be unique among element siblings of specified types. */
    @Schema(description = "Alters value uniqueness check. Property value should be unique among element siblings of specified types.")
    private List<String> uniqueAmongElements;

    /** Indicates whether value uniqueness check should be case-insensitive. */
    @Schema(description = "Whether value uniqueness check should be case-insensitive")
    private boolean caseInsensitive = false;

    /** Indicates whether property is mandatory. */
    @Schema(description = "Whether property is mandatory")
    private boolean mandatory = false;
    @Schema(description = "Whether autofocus on this property field is needed")
    private boolean autofocus = false;

    /** Indicates whether property should be used as query parameter of component URI */
    @Schema(description = "Whether property should be used as query parameter of component URI")
    private boolean query = false;


    /** List of allowed values */
    @Schema(description = "List of allowed property values")
    private List<String> allowedValues = new ArrayList<>();

    @Schema(description = "Whether custom value is allowed (for list properties)")
    private boolean allowCustomValue = true;

    /** Indicates whether property could contain multiple values. */
    @Schema(description = "Whether property property could contain multiple values")
    private boolean multiple = false;

    /** Indicates whether property contains id reference to another element. */
    @Schema(description = "Whether property contains id reference to another element")
    private boolean reference = false;

    @Schema(description = "Whether this option should be disabled if registration ingress routes on control plane is disabled")
    private String disabledOnIngressRegistrationOff;

    /**
     * Custom validation logic is used for {@link PropertyValueType#CUSTOM}.
     */
    @Nullable
    @Schema(description = "Custom validation logic")
    private PropertyValidation validation;

    @JsonIgnore
    public Object defaultValue() {
        return type.convert(defaultValue);
    }
}
