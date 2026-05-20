package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SystemDeleteException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystemLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.mcp.MCPSystemRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.system.mcp.MCPSystemRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.MCPSystemMapper;
import org.qubership.integration.platform.runtime.catalog.service.filter.MCPSystemFilterSpecificationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class MCPSystemService {
    private final MCPSystemRepository mcpSystemRepository;
    private final MCPSystemMapper mcpSystemMapper;
    private final ActionsLogService actionLogger;
    private final MCPSystemFilterSpecificationBuilder mcpSystemFilterSpecificationBuilder;
    private final ChainRepository chainRepository;

    @Autowired
    public MCPSystemService(
            MCPSystemRepository mcpSystemRepository,
            MCPSystemMapper mcpSystemMapper,
            ActionsLogService actionLogger,
            MCPSystemFilterSpecificationBuilder mcpSystemFilterSpecificationBuilder,
            ChainRepository chainRepository
    ) {
        this.mcpSystemRepository = mcpSystemRepository;
        this.mcpSystemMapper = mcpSystemMapper;
        this.actionLogger = actionLogger;
        this.mcpSystemFilterSpecificationBuilder = mcpSystemFilterSpecificationBuilder;
        this.chainRepository = chainRepository;
    }

    public List<MCPSystem> findAll(boolean withChains) {
        List<MCPSystem> systems = mcpSystemRepository.findAll();
        return withChains
                ? enrichWithChains(systems)
                : systems;
    }

    public List<MCPSystem> findAll() {
        return findAll(false);
    }

    public List<MCPSystem> findAllById(List<String> ids) {
        return mcpSystemRepository.findAllById(ids);
    }

    public Optional<MCPSystem> findById(String id) {
        return mcpSystemRepository.findById(id);
    }

    public MCPSystem create(MCPSystemRequestDTO request) {
        MCPSystem mcpSystem = new MCPSystem();
        mcpSystem.setName(request.getName());
        mcpSystem.setDescription(request.getDescription());
        mcpSystem.setIdentifier(request.getIdentifier());
        mcpSystem.setInstructions(request.getInstructions());
        return mcpSystemRepository.save(mcpSystem);
    }

    public MCPSystem create(MCPSystem system, boolean isImport) {
        system = mcpSystemRepository.save(system);
        logAction(system, isImport ? LogOperation.IMPORT : LogOperation.CREATE);
        return system;
    }

    public MCPSystem update(String id, MCPSystemRequestDTO request) {
        Optional<MCPSystem> mcpSystem = mcpSystemRepository.findById(id);
        if (mcpSystem.isEmpty()) {
            throw new EntityNotFoundException(String.format("MCP system with id %s not found", id));
        }
        MCPSystem system = mcpSystem.get();
        MCPSystem updatedSystem = mcpSystemMapper.updateWithoutLabels(system, request);

        List<MCPSystemLabel> newLabels = request.getLabels().stream().map(mcpSystemMapper::asLabel).toList();
        updateLabels(updatedSystem, updatedSystem.getLabels(), newLabels);
        return update(updatedSystem);
    }

    public MCPSystem update(MCPSystem system) {
        system = mcpSystemRepository.save(system);
        logAction(system, LogOperation.UPDATE);
        return system;
    }

    public void updateLabels(
            MCPSystem system,
            Collection<MCPSystemLabel> currentLabels,
            Collection<MCPSystemLabel> newLabels
    ) {
        Map<String, MCPSystemLabel> labelMap = newLabels.stream()
                .collect(Collectors.toMap(MCPSystemLabel::getName, Function.identity()));

        currentLabels.removeIf(label -> !labelMap.containsKey(label.getName()));
        currentLabels.forEach(label -> {
            MCPSystemLabel updatedLabel = labelMap.remove(label.getName());
            label.setTechnical(updatedLabel.isTechnical());
        });
        labelMap.values().forEach(label -> label.setSystem(system));
        currentLabels.addAll(labelMap.values());
    }

    public void deleteById(String id) {
        if (isUsedByChain(id)) {
            throw new SystemDeleteException("Service used by one or more chains");
        }
        mcpSystemRepository.findById(id).ifPresent(system -> {
            mcpSystemRepository.delete(system);
            logAction(system, LogOperation.DELETE);
        });
    }

    public List<MCPSystem> filter(String searchString, List<FilterRequestDTO> filters) {
        Specification<MCPSystem> specification = mcpSystemFilterSpecificationBuilder.buildSearchAndFilters(searchString, filters);
        return enrichWithChains(mcpSystemRepository.findAll(specification));
    }

    private void logAction(MCPSystem system, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.MCP_SYSTEM)
                .entityId(system.getId())
                .entityName(system.getName())
                .parentId(null)
                .operation(operation)
                .build());
    }

    public boolean isUsedByChain(String id) {
        return !findChainsByMcpSystemId(id).isEmpty();
    }

    private void enrichWithChains(MCPSystem system) {
        system.setChains(findChainsByMcpSystemId(system.getId()));
    }

    private List<MCPSystem> enrichWithChains(List<MCPSystem> systems) {
        systems.forEach(this::enrichWithChains);
        return systems;
    }

    private List<Chain> findChainsByMcpSystemId(String mcpSystemId) {
        return chainRepository.findChainsWithElementPropertyContainsValue(CamelNames.MCP_SERVICE_IDS, mcpSystemId);
    }
}
