package org.qubership.integration.platform.runtime.catalog.service.helpers;

import jakarta.persistence.EntityNotFoundException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ElementHelperService {

    private static final String CHAIN_ELEMENT_WITH_ID_NOT_FOUND_MESSAGE = "Can't find chain element with id: ";

    private final ElementRepository elementRepository;

    @Autowired
    public ElementHelperService(ElementRepository elementRepository) {
        this.elementRepository = elementRepository;
    }

    public ChainElement findById(String id) {
        return elementRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_ELEMENT_WITH_ID_NOT_FOUND_MESSAGE + id));
    }

    public <T extends ChainElement> T findById(String id, Class<T> elementClass) {
        ChainElement element = findById(id);
        if (elementClass.isAssignableFrom(element.getClass())) {
            return elementClass.cast(element);
        }
        return null;
    }

    public Optional<ChainElement> findByIdOptional(String id) {
        return elementRepository.findById(id);
    }

    public boolean isSystemUsedByElement(String systemId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.SYSTEM_ID)
                                ),
                        systemId)
        ));
    }

    public boolean isSpecificationGroupUsedByElement(String specificationGroupId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.SPECIFICATION_GROUP_ID)
                                ),
                        specificationGroupId)
        ));
    }

    public boolean isSystemModelUsedByElement(String modelId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.MODEL_ID)
                                ),
                        modelId)
        ));
    }

    public List<ChainElement> findBySystemIdAndOperationId(String systemId, String operationId) {
        return elementRepository.findAll((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder.function("jsonb_extract_path_text", String.class,
                                root.<String>get("properties"), builder.literal(CamelOptions.OPERATION_ID)),
                        operationId
                )
        ));
    }

    public List<ChainElement> findBySystemIdAndSpecificationGroupId(String systemId, String specificationGroupId) {
        return elementRepository.findAll((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder.function("jsonb_extract_path_text", String.class,
                                root.<String>get("properties"), builder.literal(CamelOptions.SPECIFICATION_GROUP_ID)),
                        specificationGroupId
                )
        ));
    }

    public List<ChainElement> findBySystemId(String systemId) {
        return elementRepository.findAll((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder.function("jsonb_extract_path_text", String.class,
                                root.<String>get("properties"), builder.literal(CamelOptions.SYSTEM_ID)),
                        systemId
                )
        ));
    }

    public List<Chain> findBySystemAndModelId(String systemId, String modelId) {
        List<ChainElement> elements = elementRepository.findAll((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder.function("jsonb_extract_path_text", String.class,
                                root.<String>get("properties"), builder.literal(CamelOptions.MODEL_ID)),
                        modelId
                )
        ));
        return getElementsChains(elements);
    }

    public List<Chain> findBySystemAndOperationId(String systemId, String operationId) {
        List<ChainElement> elements = findBySystemIdAndOperationId(systemId, operationId);
        return getElementsChains(elements);
    }

    public List<Chain> findBySystemAndGroupId(String systemId, String specificationGroupId) {
        List<ChainElement> elements = findBySystemIdAndSpecificationGroupId(
                systemId, specificationGroupId);
        return getElementsChains(elements);
    }

    public List<Chain> findChainBySystemId(String systemId) {
        List<ChainElement> elements = findBySystemId(systemId);
        return getElementsChains(elements);
    }

    public Map<String, List<Chain>> findBySystemIdGroupBySpecificationGroup(String systemId) {
        List<ChainElement> elements = findBySystemId(systemId);
        Map<String, List<ChainElement>> specGroupChainElement = new HashMap<>();
        for (ChainElement element : elements) {
            String specificationGroupKey = (String) element.getProperty(CamelOptions.SPECIFICATION_GROUP_ID);
            if (specificationGroupKey == null) {
                continue;
            }
            specGroupChainElement.computeIfAbsent(specificationGroupKey, s -> new ArrayList<>()).add(element);
        }
        Map<String, List<Chain>> specGroupChains = new HashMap<>();
        specGroupChainElement.forEach((key, chainElements) -> specGroupChains.put(key, getElementsChains(chainElements)));

        return specGroupChains;
    }

    private List<Chain> getElementsChains(List<ChainElement> elements) {
        return elements.stream()
                .map(ChainElement::getChain)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }
}
