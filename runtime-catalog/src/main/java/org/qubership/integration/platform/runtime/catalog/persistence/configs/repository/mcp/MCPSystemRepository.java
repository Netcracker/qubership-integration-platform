package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.mcp;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MCPSystemRepository extends JpaRepository<MCPSystem, String>, JpaSpecificationExecutor<MCPSystem> {

}
