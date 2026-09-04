package org.qubership.integration.platform.runtime.catalog.util;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.library.model.ElementProperties;
import org.qubership.integration.platform.library.model.ElementProperty;
import org.qubership.integration.platform.library.model.PropertyValueType;
import org.qubership.integration.platform.runtime.catalog.service.PropertyPlaceholderService;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The values an element starts with when nothing has set them. Creating an element writes them in,
 * and import fills in whatever the document leaves out, so that a chain compiles to the same route
 * however it reached the catalog.
 */
public final class ElementPropertyDefaults {

    private ElementPropertyDefaults() {
    }

    public static Map<String, Object> of(ElementProperties properties, String elementId, String chainId) {
        return new HashMap<>(properties.getAll().stream()
                .filter(property -> StringUtils.isNotBlank(property.getDefaultValue()))
                .collect(Collectors.toMap(
                        ElementProperty::getName,
                        property -> PropertyValueType.STRING.equals(property.getType())
                                ? PropertyPlaceholderService.replaceDefaultValuePlaceholders(
                                        property.getDefaultValue(), elementId, chainId)
                                : property.defaultValue()
                )));
    }
}
