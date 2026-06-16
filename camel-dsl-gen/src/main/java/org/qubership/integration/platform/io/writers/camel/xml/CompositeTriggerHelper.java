package org.qubership.integration.platform.io.writers.camel.xml;

import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplateInstantiationException;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class CompositeTriggerHelper {
    private final LibraryElementsService libraryService;

    @Autowired
    public CompositeTriggerHelper(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    /**
     * Create a copy of elements with separated composite triggers
     */
    public List<Element> splitCompositeTriggers(List<Element> elements) {
        List<Element> newElements = new ArrayList<>(elements);
        for (Element element : elements) {
            ElementDescriptor descriptor = libraryService.lookupElementDescriptor(element.getType()).orElse(null);
            if (descriptor != null && descriptor.getType() == ElementType.COMPOSITE_TRIGGER) {
                boolean elementHasNoParent = element.getParent()
                    .map(Element::getType)
                    .map(CamelNames.CONTAINER::equals)
                    .orElse(true);
                if (elementHasNoParent && element.getInputConnections().isEmpty()) {
                    throw new TemplateInstantiationException("Element must contains at least one input dependency.", element);
                }

                Element trigger = ElementBuilder.createNew().from(element)
                    .id(convertToAnotherUUID(element.getId()))
                    .inputConnections(Collections.emptyList())
                    .build();
                newElements.add(trigger);
            }
        }
        return newElements;
    }

    /**
     * Idempotent uuid generation from existing id
     */
    public static String convertToAnotherUUID(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes()).toString();
    }
}
