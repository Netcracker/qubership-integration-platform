package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ChainFileVersionsGetterService {
    private final List<ChainFileVersionsGetterStrategy> strategies;

    @Autowired
    public ChainFileVersionsGetterService(List<ChainFileVersionsGetterStrategy> strategies) {
        this.strategies = strategies;
    }

    public List<Integer> getVersions(ObjectNode document) throws Exception {
        return strategies.stream()
                .map(strategy -> {
                    log.trace("Applying chain file migrations getter strategy: {}", strategy.getClass().getName());
                    Optional<List<Integer>> result = strategy.getVersions(document);
                    log.trace("Result: {}", result);
                    return result;
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElseThrow(() -> new Exception("Failed to get a chain migration data"));
    }
}
