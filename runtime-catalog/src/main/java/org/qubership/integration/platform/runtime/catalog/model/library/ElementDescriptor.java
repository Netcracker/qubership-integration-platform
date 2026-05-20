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

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.model.library.chaindesign.ElementContainerDesignParameters;
import org.qubership.integration.platform.runtime.catalog.model.library.chaindesign.ElementDesignParameters;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Library element descriptor")
public class ElementDescriptor {

    /**
     * Unique name of the element
     */
    @Schema(description = "Inner type name")
    private String name;
    /**
     * Displayed name of the element
     */
    @Schema(description = "Name of the element")
    private String title;
    /**
     * Description of element's functionality
     */
    @Schema(description = "Short description")
    private String description;

    @Schema(description = "Folder (group) element belongs to")
    private String folder;

    @Schema(description = "Color type for displaying on ui")
    private String colorType;

    @Schema(description = "Color for displaying on ui")
    @Deprecated
    private String color;

    @Schema(description = "Description formatter type for sequence diagram builder")
    private String descriptionFormatter;

    @Schema(description = "Element type in terms of behavior")
    private ElementType type = ElementType.MODULE;

    @Schema(description = "Whether input dependency (link) is allowed")
    private boolean inputEnabled = true;

    @Schema(description = "How much input dependency (link) is allowed")
    private Quantity inputQuantity = Quantity.ANY;

    @Schema(description = "Whether output dependency (link) is allowed")
    private boolean outputEnabled = true;

    @Schema(description = "Whether element is a container")
    private boolean container = false;

    @Schema(description = "Whether element is ordered")
    private boolean ordered = false;

    /**
     * Specifies that the element cannot be added to any container,
     * i.e. the parent element is always null.
     */
    @Schema(description = "Whether element is allowed to be in container (group)")
    private boolean allowedInContainers = true;

    @Schema(description = "Priority property name if element is ordered")
    private String priorityProperty = "priority";

    @Schema(description = "Reuse element reference property")
    private String reuseReferenceProperty = "reuseElementId";

    @Schema(description = "Whether inner element existence is mandatory for that container element")
    private boolean mandatoryInnerElement = false;
    /**
     * Used only if <code>container</code> is <code>true</code>.
     * Consists of element names that can be children of this container element and their quantity.
     * If the map is empty, it means that there are no child restrictions.
     */
    @Schema(description = "Restricting parent elements with specified types for current element")
    private List<String> parentRestriction = new ArrayList<>();

    @Schema(description = "Restricting parent elements with specified types and quantity of elements of the type for current container element")
    private Map<String, Quantity> allowedChildren = new HashMap<>();
    @Schema(description = "Element properties")
    private ElementProperties properties = new ElementProperties();
    @Schema(description = "List of custom tabs implemented on ui for the element")
    private List<CustomTab> customTabs = new ArrayList<>();
    @Schema(description = "Whether element is deprecated")
    private boolean deprecated = false;
    @Schema(description = "Whether element is removed and no longer exists")
    private boolean unsupported = false;
    /**
     * Indicates that the container is old style, where logically nested elements were located
     * outside the container.
     */
    @Schema(description = "Whether container is old style, where logically nested elements were located outside the container")
    private boolean oldStyleContainer = false;

    /**
     * Indicates that the current element can be referenced by another element by its id.
     */
    @Schema(description = "Whether element can be referenced by another element by its id")
    private boolean referencedByAnotherElement = false;

    /**
     *  Contains element parameters for chain sequence diagram building.
     *
     *  If the new element is too complex to use these parameters - you should create custom element
     *  design processor by implementing the following interface: {@link DesignProcessor}.
     */
    @Nullable
    @Schema(description = "Element parameters for chain sequence diagram building")
    private ElementDesignParameters designParameters;

    /**
     *  Contains additional parameters for container elements (with children).
     *
     *  If the new element is too complex to use these parameters - you should create custom container element
     *  design processor by implementing the following interface: {@link ContainerDesignProcessor}.
     */
    @Nullable
    @Schema(description = "Additional element parameters for container elements (with children) for chain sequence diagram building")
    private ElementContainerDesignParameters designContainerParameters;

    public List<ElementProperty> getQueryProperties() {
        return properties.getQueryProperties();
    }

    public List<ElementProperty> getReferenceProperties() {
        return properties.getReferenceProperties();
    }
}
