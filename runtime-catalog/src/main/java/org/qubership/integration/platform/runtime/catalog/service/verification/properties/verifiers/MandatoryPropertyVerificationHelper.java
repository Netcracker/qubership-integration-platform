package org.qubership.integration.platform.runtime.catalog.service.verification.properties.verifiers;

import org.apache.commons.lang3.ObjectUtils;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.CustomTab;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementProperty;
import org.qubership.integration.platform.library.model.PropertyValueType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
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

    public boolean areMandatoryPropertiesPresent(@NonNull ChainElement element) {
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

    public boolean isMandatoryPropertyPresent(@NonNull ElementProperty propertyDescriptor, @NonNull ChainElement element) {
        if (propertyDescriptor.getType() == PropertyValueType.CUSTOM && propertyDescriptor.getValidation() != null) {
            return propertyDescriptor.getValidation().arePropertiesValid(element.getProperties());
        }

        return !propertyDescriptor.isMandatory() || ObjectUtils.isNotEmpty(element.getProperty(propertyDescriptor.getName()));
    }

    public boolean isMandatoryInnerElementPresent(@NonNull ChainElement element) {
        Optional<ElementDescriptor> descriptor = libraryService.lookupElementDescriptor(element.getType());
        if (!descriptor.map(ElementDescriptor::isMandatoryInnerElement).orElse(false)) {
            return true;
        }

        if (element instanceof ContainerChainElement container) {
            return container.getElements().stream()
                .anyMatch(child -> child.getInputDependencies().isEmpty());
        } else {
            return !element.getOutputDependencies().isEmpty();
        }
    }
}
