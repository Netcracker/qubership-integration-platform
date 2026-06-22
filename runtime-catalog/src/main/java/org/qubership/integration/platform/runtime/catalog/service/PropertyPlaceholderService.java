package org.qubership.integration.platform.runtime.catalog.service;

import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementProperty;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PropertyPlaceholderService {
    private static final String CREATED_ELEMENT_ID_PLACEHOLDER = "%%{created-element-id-placeholder}";
    private static final String CHAIN_ID_PLACEHOLDER = "%%{chain-id-placeholder}";

    private final LibraryElementsService libraryService;

    @Autowired
    public PropertyPlaceholderService(LibraryElementsService libraryService) {
        this.libraryService = libraryService;
    }

    public static String replaceDefaultValuePlaceholders(String value, String elementId,
                                                         String chainId) {
        return value.replace(CREATED_ELEMENT_ID_PLACEHOLDER, elementId)
            .replace(CHAIN_ID_PLACEHOLDER, chainId);
    }

    /**
     * See {@link org.qubership.integration.platform.library.model.ElementProperty#isResetValueOnCopy()}
     */
    public void updateResetOnCopyProperties(ChainElement copy) {
        updateResetOnCopyProperties(copy, copy.getChain().getId());
    }

    public void updateResetOnCopyProperties(ChainElement copy, String chainId) {
        Map<String, Object> copyProperties = copy.getProperties();
        libraryService.lookupElementDescriptor(copy.getType()).ifPresent(descriptor -> {
            for (ElementProperty elementProperty : descriptor.getProperties().getAll()) {
                if (elementProperty.isResetValueOnCopy()) {
                    copyProperties.put(
                        elementProperty.getName(),
                        replaceDefaultValuePlaceholders(elementProperty.getDefaultValue(),
                            copy.getId(), chainId));
                }
            }
        });
    }
}
