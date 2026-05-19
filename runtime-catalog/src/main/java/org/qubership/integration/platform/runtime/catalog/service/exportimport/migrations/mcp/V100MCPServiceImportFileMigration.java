package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class V100MCPServiceImportFileMigration implements MCPServiceImportFileMigration {
    @Override
    public int getVersion() {
        return 100;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Initial MCP service migration V100");
        return fileNode;
    }
}
