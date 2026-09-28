package org.qubership.integration.platform.library.components;

import org.qubership.integration.platform.library.constants.CamelNames;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ElementDescriptorHelper {
    private final LibraryElementsService libraryService;

    @Autowired
    public ElementDescriptorHelper(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    public ElementDescriptor resolveDescriptor(String type) {
        return libraryService.lookupElementDescriptor(type)
            .orElseGet(() -> {
                if (CamelNames.CONTAINER.equals(type)) {
                    ElementDescriptor containerDescriptor = new ElementDescriptor();
                    containerDescriptor.setType(ElementType.CONTAINER);
                    containerDescriptor.setContainer(true);
                    return containerDescriptor;
                }
                throw new IllegalArgumentException("Element of type " + type + " not found");
            });
    }

    public boolean isSwimlaneType(String type) {
        return libraryService.lookupElementDescriptor(type)
            .map(descriptor -> descriptor.getType() == ElementType.SWIMLANE)
            .orElse(false);
    }
}
