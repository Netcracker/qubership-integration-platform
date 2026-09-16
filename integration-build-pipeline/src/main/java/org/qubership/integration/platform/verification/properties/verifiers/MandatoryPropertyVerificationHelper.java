package org.qubership.integration.platform.verification.properties.verifiers;

import org.apache.commons.lang3.ObjectUtils;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.CustomTab;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementProperty;
import org.qubership.integration.platform.library.model.PropertyValueType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MandatoryPropertyVerificationHelper {
    private final LibraryElementsService libraryService;

    @Autowired
    public MandatoryPropertyVerificationHelper(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    public boolean areMandatoryPropertiesPresent(@NonNull Element element) {
        ElementDescriptor descriptor = libraryService.lookupElementDescriptor(element.getType()).orElse(null);
        if (descriptor == null) {
            return true;
        }

        for (CustomTab customTab : descriptor.getCustomTabs()) {
            if (customTab.getValidation() != null
                && !customTab.getValidation().arePropertiesValid(element.getProperties())) {
                return false;
            }
        }
        for (ElementProperty propertyDescriptor : descriptor.getProperties().getAll()) {
            if (!isMandatoryPropertyPresent(propertyDescriptor, element)) {
                return false;
            }
        }
        return true;
    }

    public boolean isMandatoryPropertyPresent(@NonNull ElementProperty propertyDescriptor, @NonNull Element element) {
        if (propertyDescriptor.getType() == PropertyValueType.CUSTOM && propertyDescriptor.getValidation() != null) {
            return propertyDescriptor.getValidation().arePropertiesValid(element.getProperties());
        }

        return !propertyDescriptor.isMandatory()
            || ObjectUtils.isNotEmpty(element.getProperties().get(propertyDescriptor.getName()));
    }

    public boolean isMandatoryInnerElementPresent(@NonNull Element element) {
        Optional<ElementDescriptor> descriptor = libraryService.lookupElementDescriptor(element.getType());
        if (!descriptor.map(ElementDescriptor::isMandatoryInnerElement).orElse(false)) {
            return true;
        }

        if (element.isContainer()) {
            return element.getChildren().stream()
                .anyMatch(child -> child.getInputConnections().isEmpty());
        } else {
            return !element.getOutputConnections().isEmpty();
        }
    }
}
