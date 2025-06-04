package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain.versions;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

public interface ChainFileVersionsGetterStrategy {
    Optional<List<Integer>> getVersions(ObjectNode document);
}
