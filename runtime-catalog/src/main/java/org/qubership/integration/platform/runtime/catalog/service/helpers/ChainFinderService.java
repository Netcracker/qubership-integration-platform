package org.qubership.integration.platform.runtime.catalog.service.helpers;

import jakarta.persistence.EntityNotFoundException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.FolderContentFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.nonNull;

@Service
public class ChainFinderService {

    private static final String CHAIN_WITH_ID_NOT_FOUND_MESSAGE = "Can't find chain with id: ";

    private final ChainRepository chainRepository;

    @Autowired
    public ChainFinderService(ChainRepository chainRepository) {
        this.chainRepository = chainRepository;
    }

    public List<Chain> findAll() {
        return chainRepository.findAll();
    }

    public List<Chain> findAllById(List<String> chainIds) {
        return chainRepository.findAllById(chainIds);
    }

    public Chain findById(String chainId) {
        return chainRepository.findById(chainId)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_WITH_ID_NOT_FOUND_MESSAGE + chainId));
    }

    public List<Chain> findChainsInFolder(String folderId, FolderContentFilter filter) {
        Specification<Chain> specification = (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("parentFolder").get("id"), folderId);
        if (nonNull(filter)) {
            specification = specification.and(filter.getSpecification());
        }
        return chainRepository.findAll(specification);
    }

    public List<Chain> findInRoot(FolderContentFilter filter) {
        Specification<Chain> specification = (root, query, criteriaBuilder) ->
                criteriaBuilder.isNull(root.get("parentFolder"));
        if (nonNull(filter)) {
            specification = specification.and(filter.getSpecification());
        }
        return chainRepository.findAll(specification);
    }

    public List<Chain> findAllChainsToRootParentFolder(String openedFolderId) {
        return chainRepository.findAllChainsToRootParentFolder(openedFolderId);
    }

    public Optional<Chain> tryFindById(String chainId) {
        return chainRepository.findById(chainId);
    }

}
